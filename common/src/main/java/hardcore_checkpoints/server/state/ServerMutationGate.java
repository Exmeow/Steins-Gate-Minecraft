package hardcore_checkpoints.server.state;

public final class ServerMutationGate {
	public boolean allowsGameplay(CheckpointServerState state) {
		return state.featureEnabled()
				&& (state.phase() == CheckpointPhase.RUNNING || state.phase() == CheckpointPhase.DEATH_COUNTDOWN);
	}

	public boolean allowsRosterMutation(CheckpointServerState state) {
		return state.featureEnabled() && !state.phase().locksRosterMutations();
	}

	public boolean allowsCheckpointCoreToggle(CheckpointServerState state) {
		return state.featureEnabled()
				&& state.phase() != CheckpointPhase.DEATH_COUNTDOWN
				&& state.phase() != CheckpointPhase.QUIESCING
				&& state.phase() != CheckpointPhase.SAVING
				&& state.phase() != CheckpointPhase.ROLLBACK_PENDING
				&& state.phase() != CheckpointPhase.RESTORING;
	}

	public boolean allowsDisable(CheckpointServerState state) {
		return switch (state.phase()) {
			case RUNNING, PAUSED, WAITING_FOR_ROSTER, ADMISSION_FROZEN, WAITING_FOR_PLAYERS,
					INITIAL_CHECKPOINT_FAILED -> true;
			default -> false;
		};
	}
}
