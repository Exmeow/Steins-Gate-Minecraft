package hardcore_checkpoints.server.state;

import java.util.Objects;

public record CheckpointServerState(
		int schemaVersion,
		CheckpointPhase phase,
		long stateSequence,
		boolean featureEnabled,
		boolean hasValidCheckpoint,
		RosterState roster,
		EpochState epoch
) {
	public static final int CURRENT_SCHEMA_VERSION = 1;

	public CheckpointServerState {
		if (schemaVersion != CURRENT_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Unsupported server-state schema: " + schemaVersion);
		}
		if (stateSequence < 0) {
			throw new IllegalArgumentException("stateSequence must not be negative");
		}
		Objects.requireNonNull(phase, "phase");
		Objects.requireNonNull(roster, "roster");
		Objects.requireNonNull(epoch, "epoch");
	}

	public static CheckpointServerState featureDisabled() {
		RosterState roster = RosterState.empty();
		return new CheckpointServerState(
				CURRENT_SCHEMA_VERSION,
				CheckpointPhase.FEATURE_DISABLED,
				0,
				false,
				false,
				roster,
				EpochState.initial(roster.version())
		);
	}

	public static CheckpointServerState enabled(RosterState roster, boolean hasValidCheckpoint) {
		return new CheckpointServerState(
				CURRENT_SCHEMA_VERSION,
				roster.members().isEmpty() ? CheckpointPhase.WAITING_FOR_ROSTER : CheckpointPhase.ADMISSION_FROZEN,
				0,
				true,
				hasValidCheckpoint,
				roster,
				EpochState.initial(roster.version())
		);
	}

	public CheckpointServerState update(
			CheckpointPhase newPhase,
			boolean newFeatureEnabled,
			boolean newHasValidCheckpoint,
			RosterState newRoster,
			EpochState newEpoch
	) {
		return new CheckpointServerState(
				schemaVersion,
				newPhase,
				stateSequence + 1,
				newFeatureEnabled,
				newHasValidCheckpoint,
				newRoster,
				newEpoch
		);
	}

	public CheckpointServerState restartBoundary() {
		CheckpointPhase restartPhase;
		if (!featureEnabled) {
			restartPhase = CheckpointPhase.FEATURE_DISABLED;
		} else if (roster.members().isEmpty()) {
			restartPhase = CheckpointPhase.WAITING_FOR_ROSTER;
		} else {
			restartPhase = CheckpointPhase.ADMISSION_FROZEN;
		}
		return update(restartPhase, featureEnabled, hasValidCheckpoint, roster, epoch.clearReady());
	}
}
