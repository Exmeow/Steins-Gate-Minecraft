package hardcore_checkpoints.control.repository;

import hardcore_checkpoints.control.ControlMetadata;
import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.WorldIdentity;
import hardcore_checkpoints.control.WorldIdentityMarker;
import hardcore_checkpoints.control.journal.ActivationSwitchJournal;
import hardcore_checkpoints.control.journal.TransactionJournal;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.control.persistence.AtomicPathMoves;
import hardcore_checkpoints.control.persistence.PathSecurity;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ControlStateRepository {
	private final AtomicFileStore fileStore;
	private final WorldIdentityMarkerRepository markerRepository;
	private final WorldBindingRepository bindingRepository;

	public ControlStateRepository() {
		this(new AtomicFileStore());
	}

	public ControlStateRepository(AtomicFileStore fileStore) {
		this.fileStore = fileStore;
		this.markerRepository = new WorldIdentityMarkerRepository(fileStore);
		this.bindingRepository = new WorldBindingRepository(fileStore);
	}

	public InitializedControl initializeDisabled(Path worldRoot) throws IOException {
		Path safeWorldRoot = PathSecurity.requireSafeDirectory(worldRoot);
		var existingMarker = markerRepository.read(safeWorldRoot);
		if (existingMarker.isPresent()) {
			WorldIdentityMarker marker = existingMarker.orElseThrow().value();
			ControlRootLayout existingLayout = ControlRootLayout.forWorld(safeWorldRoot, marker.identity().worldInstanceId());
			if (marker.featureEnabled() || Files.exists(existingLayout.instanceRoot(), LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("World identity marker already has recoverable control data: "
						+ ControlRootLayout.markerPath(safeWorldRoot));
			}
			Files.delete(ControlRootLayout.markerPath(safeWorldRoot));
		}

		WorldIdentity identity = WorldIdentity.createDisabled();
		ControlMetadata metadata = ControlMetadata.createDisabled(identity);
		ControlRootLayout layout = ControlRootLayout.forWorld(safeWorldRoot, identity.worldInstanceId());
		createOwnedDirectories(layout);
		String metadataChecksum = fileStore.write(layout.controlMetadata(), metadata);
		bindingRepository.create(layout, metadata);
		markerRepository.write(safeWorldRoot, metadata, metadataChecksum);
		return new InitializedControl(layout, metadata);
	}

	public AtomicFileStore.StoredValue<ControlMetadata> readMetadata(ControlRootLayout layout) throws IOException {
		return fileStore.readRequired(layout.controlMetadata(), ControlMetadata.class);
	}

	public ControlMetadata startNewActivation(ControlRootLayout layout) throws IOException {
		recoverActivationSwitch(layout);
		ControlMetadata current = readMetadata(layout).value();
		if (current.featureEnabled()) {
			throw new IOException("Checkpoint functionality is already enabled for this world instance");
		}

		ActivationSwitchJournal journal = ActivationSwitchJournal.prepare(
				current.identity().worldInstanceId(),
				current.identity().activationId(),
				UUID.randomUUID()
		);
		fileStore.write(layout.activationSwitchJournal(), journal);
		return completeActivationSwitch(layout, current, journal);
	}

	public ControlMetadata disable(ControlRootLayout layout) throws IOException {
		recoverActivationSwitch(layout);
		ControlMetadata disabled = readMetadata(layout).value().withFeatureEnabled(false);
		publishMetadata(layout, disabled);
		return disabled;
	}

	public void abandonMissingControlRoot(Path worldRoot) throws IOException {
		Path safeWorldRoot = PathSecurity.requireSafeDirectory(worldRoot);
		var storedMarker = markerRepository.read(safeWorldRoot)
				.orElseThrow(() -> new IOException("World has no checkpoint identity marker"));
		WorldIdentityMarker marker = storedMarker.value();
		ControlRootLayout layout = ControlRootLayout.forWorld(safeWorldRoot, marker.identity().worldInstanceId());
		if (Files.exists(layout.instanceRoot(), LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Control root still exists; refusing to abandon potentially recoverable control data");
		}
		WorldIdentityMarker disabled = new WorldIdentityMarker(
				marker.schemaVersion(),
				false,
				marker.identity(),
				marker.controlMetadataSha256()
		);
		fileStore.write(ControlRootLayout.markerPath(safeWorldRoot), disabled);
	}

	public void recoverActivationSwitch(ControlRootLayout layout) throws IOException {
		var storedJournal = fileStore.readOptional(
				layout.activationSwitchJournal(),
				ActivationSwitchJournal.class
		);
		if (storedJournal.isEmpty()) {
			return;
		}

		ActivationSwitchJournal journal = storedJournal.get().value();
		ControlMetadata current = readMetadata(layout).value();
		if (!journal.worldInstanceId().equals(current.identity().worldInstanceId())) {
			throw new IOException("Activation switch journal belongs to another world instance");
		}
		completeActivationSwitch(layout, current, journal);
	}

	public String writeTransaction(ControlRootLayout layout, TransactionJournal transaction) throws IOException {
		ControlMetadata metadata = readMetadata(layout).value();
		if (!transaction.worldLineageId().equals(metadata.identity().worldLineageId())
				|| !transaction.worldInstanceId().equals(metadata.identity().worldInstanceId())) {
			throw new IOException("Transaction identity does not match the control root");
		}
		Path target = PathSecurity.resolveInside(
				layout.transactionsRoot(),
				transaction.transactionId() + ".json"
		);
		return fileStore.write(target, transaction);
	}

	public List<TransactionJournal> readTransactions(ControlRootLayout layout) throws IOException {
		if (!Files.isDirectory(layout.transactionsRoot(), LinkOption.NOFOLLOW_LINKS)) {
			return List.of();
		}
		PathSecurity.requireSafeDirectory(layout.transactionsRoot());
		List<TransactionJournal> transactions = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(layout.transactionsRoot(), "*.json")) {
			for (Path path : stream) {
				transactions.add(fileStore.readRequired(path, TransactionJournal.class).value());
			}
		}
		return List.copyOf(transactions);
	}

	public AtomicFileStore fileStore() {
		return fileStore;
	}

	public WorldIdentityMarkerRepository markerRepository() {
		return markerRepository;
	}

	public WorldBindingRepository bindingRepository() {
		return bindingRepository;
	}

	private ControlMetadata completeActivationSwitch(
			ControlRootLayout layout,
			ControlMetadata current,
			ActivationSwitchJournal originalJournal
	) throws IOException {
		ActivationSwitchJournal journal = originalJournal;
		if (journal.phase().ordinal() <= ActivationSwitchJournal.Phase.PREPARED.ordinal()) {
			isolateOldCycle(layout, journal);
			journal = journal.withPhase(ActivationSwitchJournal.Phase.OLD_CYCLE_ISOLATED);
			fileStore.write(layout.activationSwitchJournal(), journal);
		}

		if (journal.phase().ordinal() <= ActivationSwitchJournal.Phase.OLD_CYCLE_ISOLATED.ordinal()) {
			PathSecurity.createDirectoriesSecure(layout.instanceRoot(), layout.cycle(journal.newActivationId()));
			PathSecurity.createDirectoriesSecure(layout.instanceRoot(), layout.transactionsRoot());
			PathSecurity.createDirectoriesSecure(layout.instanceRoot(), layout.checkpointsRoot());
			journal = journal.withPhase(ActivationSwitchJournal.Phase.NEW_CYCLE_CREATED);
			fileStore.write(layout.activationSwitchJournal(), journal);
		}

		ControlMetadata activated = current.beginActivation(journal.newActivationId());
		if (journal.phase().ordinal() <= ActivationSwitchJournal.Phase.NEW_CYCLE_CREATED.ordinal()) {
			publishMetadata(layout, activated);
			journal = journal.withPhase(ActivationSwitchJournal.Phase.METADATA_PUBLISHED);
			fileStore.write(layout.activationSwitchJournal(), journal);
		} else {
			publishMetadata(layout, activated);
		}

		Files.deleteIfExists(layout.activationSwitchJournal());
		return activated;
	}

	private void isolateOldCycle(ControlRootLayout layout, ActivationSwitchJournal journal) throws IOException {
		if (journal.oldActivationId() == null) {
			return;
		}
		Path source = layout.cycle(journal.oldActivationId());
		Path target = PathSecurity.resolveInside(layout.obsoleteRoot(), journal.obsoleteDirectoryName());
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			PathSecurity.requireSafeDirectory(target);
			return;
		}
		if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Current activation directory is missing: " + source);
		}
		PathSecurity.requireSafeDirectory(source);
		stageActivationArtifacts(layout, source);
		AtomicPathMoves.move(
				source,
				target,
				false,
				"Filesystem does not support atomic activation isolation"
		);
	}

	private static void stageActivationArtifacts(ControlRootLayout layout, Path cycleRoot) throws IOException {
		for (String name : List.of(
				"server-state.json",
				"roster.json",
				"checkpoint-pointer.json",
				"checkpoints",
				"transactions"
		)) {
			Path source = layout.instanceRoot().resolve(name);
			Path target = cycleRoot.resolve(name);
			if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
				continue;
			}
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Activation artifact exists in both active and staged locations: " + name);
			}
			if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
				PathSecurity.requireSafeTree(source);
			} else {
				PathSecurity.requireSafeFileOrMissing(source);
			}
			AtomicPathMoves.move(
					source,
					target,
					false,
					"Filesystem does not support atomic activation artifact staging: " + name
			);
		}
	}

	private String publishMetadata(ControlRootLayout layout, ControlMetadata metadata) throws IOException {
		String checksum = fileStore.write(layout.controlMetadata(), metadata);
		markerRepository.write(layout.worldRoot(), metadata, checksum);
		return checksum;
	}

	private static void createOwnedDirectories(ControlRootLayout layout) throws IOException {
		PathSecurity.createDirectoriesSecure(layout.worldParent(), layout.ownershipRoot());
		PathSecurity.createDirectoriesSecure(layout.ownershipRoot(), layout.instancesRoot());
		PathSecurity.createDirectoriesSecure(layout.instancesRoot(), layout.instanceRoot());
		PathSecurity.createDirectoriesSecure(layout.instanceRoot(), layout.cyclesRoot());
		PathSecurity.createDirectoriesSecure(layout.instanceRoot(), layout.obsoleteRoot());
		PathSecurity.createDirectoriesSecure(layout.instanceRoot(), layout.transactionsRoot());
		PathSecurity.createDirectoriesSecure(layout.instanceRoot(), layout.checkpointsRoot());
	}

	public record InitializedControl(ControlRootLayout layout, ControlMetadata metadata) {
	}
}
