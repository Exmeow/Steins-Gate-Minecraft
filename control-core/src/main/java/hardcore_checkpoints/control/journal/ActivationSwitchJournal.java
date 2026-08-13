package hardcore_checkpoints.control.journal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ActivationSwitchJournal(
		int schemaVersion,
		UUID journalId,
		UUID worldInstanceId,
		UUID oldActivationId,
		UUID newActivationId,
		String obsoleteDirectoryName,
		Phase phase,
		String startedAtUtc
) {
	public ActivationSwitchJournal {
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("Unsupported activation switch journal schema: " + schemaVersion);
		}
		Objects.requireNonNull(journalId, "journalId");
		Objects.requireNonNull(worldInstanceId, "worldInstanceId");
		Objects.requireNonNull(newActivationId, "newActivationId");
		Objects.requireNonNull(obsoleteDirectoryName, "obsoleteDirectoryName");
		Objects.requireNonNull(phase, "phase");
		Objects.requireNonNull(startedAtUtc, "startedAtUtc");
	}

	public static ActivationSwitchJournal prepare(UUID worldInstanceId, UUID oldActivationId, UUID newActivationId) {
		UUID journalId = UUID.randomUUID();
		String obsoleteName = oldActivationId == null
				? "none." + journalId
				: oldActivationId + "." + journalId;
		return new ActivationSwitchJournal(
				1,
				journalId,
				worldInstanceId,
				oldActivationId,
				newActivationId,
				obsoleteName,
				Phase.PREPARED,
				Instant.now().toString()
		);
	}

	public ActivationSwitchJournal withPhase(Phase newPhase) {
		return new ActivationSwitchJournal(
				schemaVersion,
				journalId,
				worldInstanceId,
				oldActivationId,
				newActivationId,
				obsoleteDirectoryName,
				newPhase,
				startedAtUtc
		);
	}

	public ActivationSwitchJournal rebind(UUID newWorldInstanceId) {
		return new ActivationSwitchJournal(
				schemaVersion,
				journalId,
				Objects.requireNonNull(newWorldInstanceId, "newWorldInstanceId"),
				oldActivationId,
				newActivationId,
				obsoleteDirectoryName,
				phase,
				startedAtUtc
		);
	}

	public enum Phase {
		PREPARED,
		OLD_CYCLE_ISOLATED,
		NEW_CYCLE_CREATED,
		METADATA_PUBLISHED
	}
}
