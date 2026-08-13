package hardcore_checkpoints.recovery;

import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.journal.TransactionJournal;
import hardcore_checkpoints.control.repository.ControlStateRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class RollbackJournalRepository {
	private final ControlStateRepository controls;

	public RollbackJournalRepository(ControlStateRepository controls) {
		this.controls = Objects.requireNonNull(controls, "controls");
	}

	public void save(ControlRootLayout layout, DeathTransaction death, RollbackJournal journal) throws IOException {
		if (!journal.belongsTo(death)) {
			throw new IOException("Rollback journal does not match the death transaction");
		}
		controls.writeTransaction(layout, journal.toTransactionJournal());
	}

	public Optional<RollbackJournal> find(ControlRootLayout layout, DeathTransaction death) throws IOException {
		List<RollbackJournal> matches = new ArrayList<>();
		for (TransactionJournal transaction : controls.readTransactions(layout)) {
			if (!RollbackJournal.TRANSACTION_TYPE.equals(transaction.transactionType())) {
				continue;
			}
			RollbackJournal journal;
			try {
				journal = RollbackJournal.fromTransactionJournal(transaction);
			} catch (IllegalArgumentException exception) {
				throw new IOException("Invalid rollback filesystem journal " + transaction.transactionId(), exception);
			}
			if (journal.belongsTo(death)) {
				matches.add(journal);
			}
		}
		if (matches.size() > 1) {
			throw new IOException("Multiple rollback filesystem journals exist for death transaction "
					+ death.transactionId());
		}
		return matches.stream().findFirst();
	}
}
