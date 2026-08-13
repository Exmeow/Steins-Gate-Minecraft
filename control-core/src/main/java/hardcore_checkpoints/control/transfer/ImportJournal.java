package hardcore_checkpoints.control.transfer;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ImportJournal(
		int schemaVersion,
		UUID importId,
		UUID newWorldInstanceId,
		String temporaryWorldPath,
		String targetWorldPath,
		String temporaryControlPath,
		String targetControlPath,
		Phase phase,
		String startedAtUtc
) {
	public ImportJournal {
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("Unsupported import journal schema: " + schemaVersion);
		}
		Objects.requireNonNull(importId, "importId");
		Objects.requireNonNull(newWorldInstanceId, "newWorldInstanceId");
		Objects.requireNonNull(temporaryWorldPath, "temporaryWorldPath");
		Objects.requireNonNull(targetWorldPath, "targetWorldPath");
		Objects.requireNonNull(temporaryControlPath, "temporaryControlPath");
		Objects.requireNonNull(targetControlPath, "targetControlPath");
		Objects.requireNonNull(phase, "phase");
		Objects.requireNonNull(startedAtUtc, "startedAtUtc");
	}

	public static ImportJournal create(
			UUID newWorldInstanceId,
			String temporaryWorldPath,
			String targetWorldPath,
			String temporaryControlPath,
			String targetControlPath
	) {
		return new ImportJournal(
				1,
				UUID.randomUUID(),
				newWorldInstanceId,
				temporaryWorldPath,
				targetWorldPath,
				temporaryControlPath,
				targetControlPath,
				Phase.EXTRACTING,
				Instant.now().toString()
		);
	}

	public ImportJournal withPhase(Phase newPhase) {
		return new ImportJournal(
				schemaVersion,
				importId,
				newWorldInstanceId,
				temporaryWorldPath,
				targetWorldPath,
				temporaryControlPath,
				targetControlPath,
				newPhase,
				startedAtUtc
		);
	}

	public enum Phase {
		EXTRACTING,
		VALIDATED,
		CONTROL_PUBLISHED,
		WORLD_PUBLISHED
	}
}
