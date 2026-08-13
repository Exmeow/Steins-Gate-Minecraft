package hardcore_checkpoints.recovery;

import hardcore_checkpoints.checkpoint.CheckpointPointer;
import hardcore_checkpoints.control.ControlMetadata;
import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.WorldIdentity;
import hardcore_checkpoints.control.journal.TransactionJournal;
import hardcore_checkpoints.control.repository.ControlStateRepository;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DeathTransactionRepository {
	private final ControlStateRepository controlRepository;

	public DeathTransactionRepository() {
		this(new ControlStateRepository());
	}

	public DeathTransactionRepository(ControlStateRepository controlRepository) {
		this.controlRepository = Objects.requireNonNull(controlRepository, "controlRepository");
	}

	public DeathTransaction begin(
			ControlRootLayout layout,
			CheckpointPointer pointer,
			UUID firstDeathPlayerId,
			Instant now
	) throws IOException {
		ControlMetadata metadata = controlRepository.readMetadata(layout).value();
		if (!metadata.featureEnabled() || metadata.identity().activationId() == null) {
			throw new IOException("Cannot begin a death transaction while checkpoint functionality is disabled");
		}
		validatePointer(metadata.identity(), pointer);
		Optional<DeathTransaction> existing = findActive(layout, metadata.identity());
		if (existing.isPresent()) {
			return existing.orElseThrow();
		}
		DeathTransaction transaction = DeathTransaction.start(
				metadata.identity(),
				pointer.latestCheckpointId(),
				firstDeathPlayerId,
				now
		);
		save(layout, transaction);
		return transaction;
	}

	public void save(ControlRootLayout layout, DeathTransaction transaction) throws IOException {
		ControlMetadata metadata = controlRepository.readMetadata(layout).value();
		if (!transaction.belongsTo(metadata.identity())) {
			throw new IOException("Death transaction identity does not match the active world activation");
		}
		controlRepository.writeTransaction(layout, transaction.toJournal());
	}

	public Optional<DeathTransaction> findActive(ControlRootLayout layout) throws IOException {
		WorldIdentity identity = controlRepository.readMetadata(layout).value().identity();
		return findActive(layout, identity);
	}

	public Optional<DeathTransaction> findActive(ControlRootLayout layout, WorldIdentity identity) throws IOException {
		List<DeathTransaction> active = new ArrayList<>();
		for (TransactionJournal journal : controlRepository.readTransactions(layout)) {
			if (!DeathTransaction.TRANSACTION_TYPE.equals(journal.transactionType())) {
				continue;
			}
			DeathTransaction transaction;
			try {
				transaction = DeathTransaction.fromJournal(journal);
			} catch (IllegalArgumentException exception) {
				throw new IOException("Invalid persisted death transaction " + journal.transactionId(), exception);
			}
			if (transaction.belongsTo(identity) && transaction.stage().blocksWorldEntry()) {
				active.add(transaction);
			}
		}
		if (active.size() > 1) {
			throw new IOException("Multiple active death transactions exist for activation " + identity.activationId());
		}
		return active.stream().findFirst();
	}

	public DeathTransaction requireActive(ControlRootLayout layout) throws IOException {
		return findActive(layout).orElseThrow(() -> new IOException("No active death transaction exists"));
	}

	private static void validatePointer(WorldIdentity identity, CheckpointPointer pointer) throws IOException {
		if (!identity.equals(pointer.worldIdentity())) {
			throw new IOException("Latest checkpoint belongs to another world activation");
		}
	}
}
