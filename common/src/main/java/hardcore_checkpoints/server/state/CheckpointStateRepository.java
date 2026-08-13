package hardcore_checkpoints.server.state;

import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.persistence.AtomicFileStore;

import java.io.IOException;

public final class CheckpointStateRepository {
	private final AtomicFileStore fileStore;

	public CheckpointStateRepository(AtomicFileStore fileStore) {
		this.fileStore = fileStore;
	}

	public void save(ControlRootLayout layout, CheckpointServerState state) throws IOException {
		PersistentState persistent = new PersistentState(
				1,
				state.phase(),
				state.stateSequence(),
				state.featureEnabled(),
				state.hasValidCheckpoint(),
				state.epoch().number()
		);
		fileStore.write(layout.instanceRoot().resolve("server-state.json"), persistent);
		fileStore.write(layout.instanceRoot().resolve("roster.json"), state.roster());
	}

	public CheckpointServerState loadOrCreate(
			ControlRootLayout layout,
			boolean featureEnabled,
			boolean hasValidCheckpoint
	) throws IOException {
		var storedRoster = fileStore.readOptional(layout.instanceRoot().resolve("roster.json"), RosterState.class);
		RosterState roster = storedRoster.map(AtomicFileStore.StoredValue::value).orElseGet(RosterState::empty);
		var storedState = fileStore.readOptional(layout.instanceRoot().resolve("server-state.json"), PersistentState.class);
		if (storedState.isEmpty()) {
			CheckpointServerState created = featureEnabled
					? CheckpointServerState.enabled(roster, hasValidCheckpoint)
					: CheckpointServerState.featureDisabled();
			save(layout, created);
			return created;
		}
		PersistentState persistent = storedState.get().value();
		CheckpointPhase restartPhase;
		if (!featureEnabled) {
			restartPhase = CheckpointPhase.FEATURE_DISABLED;
		} else if (persistent.phase().isDeathRecovery()) {
			restartPhase = persistent.phase();
		} else if (roster.members().isEmpty()) {
			restartPhase = CheckpointPhase.WAITING_FOR_ROSTER;
		} else {
			restartPhase = CheckpointPhase.ADMISSION_FROZEN;
		}
		return new CheckpointServerState(
				CheckpointServerState.CURRENT_SCHEMA_VERSION,
				restartPhase,
				persistent.stateSequence() + 1,
				featureEnabled,
				persistent.hasValidCheckpoint(),
				roster,
				new EpochState(persistent.epochNumber() + 1, java.util.UUID.randomUUID(), roster.version(), ReadyState.empty())
		);
	}

	private record PersistentState(
			int schemaVersion,
			CheckpointPhase phase,
			long stateSequence,
			boolean featureEnabled,
			boolean hasValidCheckpoint,
			long epochNumber
	) {
		private PersistentState {
			if (schemaVersion != 1) {
				throw new IllegalArgumentException("Unsupported persistent server-state schema: " + schemaVersion);
			}
		}
	}
}
