package hardcore_checkpoints.recovery;

import hardcore_checkpoints.checkpoint.CheckpointManifest;
import hardcore_checkpoints.checkpoint.CheckpointPointer;
import hardcore_checkpoints.checkpoint.SnapshotVerifier;
import hardcore_checkpoints.control.ControlMetadata;
import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.WorldIdentity;
import hardcore_checkpoints.control.WorldIdentityMarker;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.control.persistence.AtomicPathMoves;
import hardcore_checkpoints.control.persistence.PathSecurity;
import hardcore_checkpoints.control.repository.ControlStateRepository;
import hardcore_checkpoints.control.transfer.WorldActivityProbe;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

public final class OfflineRollbackService {
	private static final System.Logger LOGGER = System.getLogger(OfflineRollbackService.class.getName());

	private final WorldActivityProbe activityProbe;
	private final ControlStateRepository controls;
	private final DeathTransactionRepository deaths;
	private final RollbackJournalRepository rollbacks;
	private final AtomicFileStore fileStore;
	private final SnapshotVerifier verifier;

	public OfflineRollbackService(WorldActivityProbe activityProbe) {
		this(activityProbe, new ControlStateRepository());
	}

	public OfflineRollbackService(WorldActivityProbe activityProbe, ControlStateRepository controls) {
		this.activityProbe = Objects.requireNonNull(activityProbe, "activityProbe");
		this.controls = Objects.requireNonNull(controls, "controls");
		this.deaths = new DeathTransactionRepository(controls);
		this.rollbacks = new RollbackJournalRepository(controls);
		this.fileStore = controls.fileStore();
		this.verifier = new SnapshotVerifier(fileStore);
	}

	public RestoreResult restoreStopped(ControlRootLayout layout, Instant now) throws IOException {
		Objects.requireNonNull(now, "now");
		ensureWorldStopped(layout.worldRoot());
		DeathTransaction death = deaths.requireActive(layout);
		if (death.stage() == DeathTransactionStage.RECOVERY_FAILED) {
			throw new IOException("Recovery is frozen after failure " + death.failureCode()
					+ "; an administrator must retry the same transaction");
		}
		if (death.stage() == DeathTransactionStage.COUNTDOWN) {
			death = death.transitionTo(DeathTransactionStage.ROLLBACK_PENDING, now);
			deaths.save(layout, death);
		}
		if (death.stage() == DeathTransactionStage.ROLLBACK_PENDING) {
			death = death.transitionTo(DeathTransactionStage.RESTORING, now);
			deaths.save(layout, death);
		}
		if (death.stage() != DeathTransactionStage.RESTORING) {
			throw new IOException("Death transaction cannot be restored from stage " + death.stage());
		}

		RollbackJournal journal = rollbacks.find(layout, death).orElse(null);
		if (journal != null && journal.stage() == RollbackFilesystemStage.HEALTH_VERIFIED) {
			DeathTransaction recovered = death.transitionTo(DeathTransactionStage.RECOVERED, now);
			deaths.save(layout, recovered);
			return result(layout, journal);
		}

		try {
			VerifiedCheckpoint checkpoint = verifyCheckpoint(layout, death);
			if (journal == null) {
				journal = RollbackJournal.prepare(death, layout.worldRoot(), now);
				rollbacks.save(layout, death, journal);
			}
			validateJournalPaths(layout, journal);
			journal = rescueWorld(layout, death, journal, now);
			journal = stageSnapshot(layout, death, journal, checkpoint, now);
			journal = publishWorld(layout, death, journal, checkpoint, now);
			return result(layout, journal);
		} catch (IOException | RuntimeException exception) {
			DeathTransaction latest = deaths.requireActive(layout);
			if (latest.stage() == DeathTransactionStage.RESTORING) {
				String code = classifyFailure(exception);
				String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
				deaths.save(layout, latest.fail(code, message, Instant.now()));
			}
			throw exception;
		}
	}

	public RestoreResult retryStopped(ControlRootLayout layout, Instant now) throws IOException {
		DeathTransaction failed = deaths.requireActive(layout);
		if (failed.stage() != DeathTransactionStage.RECOVERY_FAILED) {
			throw new IOException("Recovery retry requires RECOVERY_FAILED, found " + failed.stage());
		}
		deaths.save(layout, failed.transitionTo(DeathTransactionStage.RESTORING, now));
		return restoreStopped(layout, now);
	}

	public RestoreResult confirmHealthy(
			ControlRootLayout layout,
			WorldIdentity reportedIdentity,
			java.util.UUID reportedCheckpointId,
			Instant now
	) throws IOException {
		DeathTransaction death = deaths.requireActive(layout);
		if (death.stage() != DeathTransactionStage.RESTORING
				|| !death.belongsTo(reportedIdentity)
				|| !death.checkpointId().equals(reportedCheckpointId)) {
			throw new IOException("Health report does not match the active recovery transaction");
		}
		RollbackJournal journal = rollbacks.find(layout, death)
				.orElseThrow(() -> new IOException("Recovery has no filesystem journal"));
		if (journal.stage() == RollbackFilesystemStage.WORLD_PUBLISHED) {
			journal = journal.transitionTo(RollbackFilesystemStage.HEALTH_VERIFIED, now);
			rollbacks.save(layout, death, journal);
		} else if (journal.stage() != RollbackFilesystemStage.HEALTH_VERIFIED) {
			throw new IOException("Recovered world has not been published: " + journal.stage());
		}
		deaths.save(layout, death.transitionTo(DeathTransactionStage.RECOVERED, now));
		try {
			deleteRecoveryArtifact(layout.worldParent(), layout.worldParent().resolve(journal.stagingDirectoryName()));
			deleteRecoveryArtifact(layout.worldParent(), layout.worldParent().resolve(journal.rescueDirectoryName()));
		} catch (IOException cleanupFailure) {
			LOGGER.log(
					System.Logger.Level.WARNING,
					"Recovery " + death.transactionId() + " is healthy, but retained cleanup artifacts under " + layout.worldParent(),
					cleanupFailure
			);
		}
		return result(layout, journal);
	}

	private VerifiedCheckpoint verifyCheckpoint(ControlRootLayout layout, DeathTransaction death) throws IOException {
		ControlMetadata metadata = controls.readMetadata(layout).value();
		if (!death.belongsTo(metadata.identity())) {
			throw new IOException("Death transaction does not belong to the active world activation");
		}
		CheckpointPointer pointer = verifier.verifyPublished(layout, metadata.identity());
		if (!pointer.latestCheckpointId().equals(death.checkpointId())) {
			throw new IOException("Death transaction checkpoint is no longer the published latest checkpoint");
		}
		Path checkpointRoot = PathSecurity.requireSafeDirectory(
				layout.checkpointsRoot().resolve(death.checkpointId().toString())
		);
		CheckpointManifest manifest = fileStore.readRequired(
				checkpointRoot.resolve("manifest.json"),
				CheckpointManifest.class
		).value();
		return new VerifiedCheckpoint(checkpointRoot.resolve("world"), manifest, metadata);
	}

	private RollbackJournal rescueWorld(
			ControlRootLayout layout,
			DeathTransaction death,
			RollbackJournal journal,
			Instant now
	) throws IOException {
		if (journal.stage().ordinal() > RollbackFilesystemStage.PREPARED.ordinal()) {
			return journal;
		}
		Path world = layout.worldRoot();
		Path rescue = layout.worldParent().resolve(journal.rescueDirectoryName());
		boolean worldExists = Files.exists(world, LinkOption.NOFOLLOW_LINKS);
		boolean rescueExists = Files.exists(rescue, LinkOption.NOFOLLOW_LINKS);
		if (worldExists && !rescueExists) {
			PathSecurity.requireSafeDirectory(world);
			AtomicPathMoves.move(world, rescue, false, "Atomic world rescue move is not supported");
		} else if (worldExists || !rescueExists) {
			throw new IOException("Ambiguous rollback rescue state: world=" + worldExists + ", rescue=" + rescueExists);
		}
		journal = journal.transitionTo(RollbackFilesystemStage.WORLD_RESCUED, now);
		rollbacks.save(layout, death, journal);
		return journal;
	}

	private RollbackJournal stageSnapshot(
			ControlRootLayout layout,
			DeathTransaction death,
			RollbackJournal journal,
			VerifiedCheckpoint checkpoint,
			Instant now
	) throws IOException {
		if (journal.stage().ordinal() > RollbackFilesystemStage.WORLD_RESCUED.ordinal()) {
			return journal;
		}
		Path staging = layout.worldParent().resolve(journal.stagingDirectoryName());
		deleteRecoveryArtifact(layout.worldParent(), staging);
		Files.createDirectory(staging);
		copyTree(checkpoint.worldSnapshot(), staging);
		verifier.verifyWorldSnapshot(staging, checkpoint.manifest());
		verifyRestoredMarker(staging, checkpoint.metadata());
		journal = journal.transitionTo(RollbackFilesystemStage.SNAPSHOT_STAGED, now);
		rollbacks.save(layout, death, journal);
		return journal;
	}

	private RollbackJournal publishWorld(
			ControlRootLayout layout,
			DeathTransaction death,
			RollbackJournal journal,
			VerifiedCheckpoint checkpoint,
			Instant now
	) throws IOException {
		if (journal.stage().ordinal() > RollbackFilesystemStage.SNAPSHOT_STAGED.ordinal()) {
			return journal;
		}
		Path world = layout.worldRoot();
		Path staging = layout.worldParent().resolve(journal.stagingDirectoryName());
		boolean worldExists = Files.exists(world, LinkOption.NOFOLLOW_LINKS);
		boolean stagingExists = Files.exists(staging, LinkOption.NOFOLLOW_LINKS);
		if (!worldExists && stagingExists) {
			AtomicPathMoves.move(staging, world, false, "Atomic restored-world publication is not supported");
		} else if (!worldExists || stagingExists) {
			throw new IOException("Ambiguous rollback publication state: world=" + worldExists
					+ ", staging=" + stagingExists);
		}
		verifier.verifyWorldSnapshot(world, checkpoint.manifest());
		verifyRestoredMarker(world, checkpoint.metadata());
		journal = journal.transitionTo(RollbackFilesystemStage.WORLD_PUBLISHED, now);
		rollbacks.save(layout, death, journal);
		return journal;
	}

	private void verifyRestoredMarker(Path restoredWorld, ControlMetadata metadata) throws IOException {
		WorldIdentityMarker marker = controls.markerRepository().read(restoredWorld)
				.orElseThrow(() -> new IOException("Restored checkpoint is missing its world identity marker"))
				.value();
		if (!marker.featureEnabled() || !marker.identity().equals(metadata.identity())) {
			throw new IOException("Restored checkpoint identity marker does not match the control root");
		}
	}

	private void ensureWorldStopped(Path worldRoot) throws IOException {
		if (activityProbe.isWorldRunning(worldRoot)) {
			throw new IOException("Refusing to restore a world that is still running: " + worldRoot);
		}
	}

	private static void validateJournalPaths(ControlRootLayout layout, RollbackJournal journal) throws IOException {
		String actualName = Objects.requireNonNull(layout.worldRoot().getFileName()).toString();
		if (!actualName.equals(journal.worldDirectoryName())) {
			throw new IOException("Rollback journal targets another world directory");
		}
		PathSecurity.resolveInside(layout.worldParent(), journal.rescueDirectoryName());
		PathSecurity.resolveInside(layout.worldParent(), journal.stagingDirectoryName());
	}

	private static void copyTree(Path sourceRoot, Path targetRoot) throws IOException {
		Path safeSource = PathSecurity.requireSafeDirectory(sourceRoot);
		Path safeTarget = PathSecurity.requireSafeDirectory(targetRoot);
		PathSecurity.requireSafeTree(safeSource);
		try (var paths = Files.walk(safeSource)) {
			for (Path source : paths.sorted().toList()) {
				if (source.equals(safeSource)) {
					continue;
				}
				Path relative = safeSource.relativize(source);
				Path target = safeTarget.resolve(relative).normalize();
				if (!target.startsWith(safeTarget)) {
					throw new IOException("Checkpoint restore path escaped staging directory: " + relative);
				}
				BasicFileAttributes attributes = Files.readAttributes(
						source,
						BasicFileAttributes.class,
						LinkOption.NOFOLLOW_LINKS
				);
				if (attributes.isDirectory()) {
					Files.createDirectory(target);
				} else if (attributes.isRegularFile()) {
					Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
					try (FileChannel channel = FileChannel.open(target, StandardOpenOption.WRITE)) {
						channel.force(true);
					}
				} else {
					throw new IOException("Unsupported checkpoint restore file type: " + source);
				}
			}
		}
	}

	private static void deleteRecoveryArtifact(Path parent, Path target) throws IOException {
		Path safeParent = PathSecurity.requireSafeDirectory(parent);
		Path normalized = target.toAbsolutePath().normalize();
		if (!normalized.startsWith(safeParent) || normalized.equals(safeParent)
				|| !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		PathSecurity.requireSafeTree(normalized);
		try (var paths = Files.walk(normalized)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static String classifyFailure(Exception exception) {
		if (exception.getMessage() != null && exception.getMessage().toLowerCase(java.util.Locale.ROOT).contains("manifest")) {
			return "CHECKPOINT_VERIFICATION_FAILED";
		}
		return "FILESYSTEM_RESTORE_FAILED";
	}

	private static RestoreResult result(ControlRootLayout layout, RollbackJournal journal) {
		return new RestoreResult(
				journal.stage(),
				layout.worldRoot(),
				layout.worldParent().resolve(journal.rescueDirectoryName()),
				layout.worldParent().resolve(journal.stagingDirectoryName()),
				journal.checkpointId(),
				journal.deathTransactionId()
		);
	}

	private record VerifiedCheckpoint(Path worldSnapshot, CheckpointManifest manifest, ControlMetadata metadata) {
	}

	public record RestoreResult(
			RollbackFilesystemStage stage,
			Path worldRoot,
			Path rescueRoot,
			Path stagingRoot,
			java.util.UUID checkpointId,
			java.util.UUID deathTransactionId
	) {
	}
}
