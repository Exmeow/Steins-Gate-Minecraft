package hardcore_checkpoints.server.state;

import java.util.Set;
import java.util.UUID;

public sealed interface CheckpointEvent permits
		CheckpointEvent.EnableCycle,
		CheckpointEvent.DisableCommitted,
		CheckpointEvent.AddRosterMember,
		CheckpointEvent.RemoveRosterMember,
		CheckpointEvent.PlayConnected,
		CheckpointEvent.Disconnected,
		CheckpointEvent.PlayPresenceObserved,
		CheckpointEvent.MarkReady,
		CheckpointEvent.WithdrawReady,
		CheckpointEvent.Tick,
		CheckpointEvent.CheckpointRequested,
		CheckpointEvent.SavingStarted,
		CheckpointEvent.CheckpointSucceeded,
		CheckpointEvent.CheckpointFailed,
		CheckpointEvent.DeathCountdownStarted,
		CheckpointEvent.ServerActuallyStopping,
		CheckpointEvent.RollbackSucceeded,
		CheckpointEvent.RollbackFailed {

	record EnableCycle() implements CheckpointEvent {
	}

	record DisableCommitted() implements CheckpointEvent {
	}

	record AddRosterMember(UUID playerId, long expectedRosterVersion, UUID requestId, boolean pending) implements CheckpointEvent {
	}

	record RemoveRosterMember(UUID playerId, long expectedRosterVersion, UUID requestId) implements CheckpointEvent {
	}

	record PlayConnected(UUID playerId) implements CheckpointEvent {
	}

	record Disconnected(UUID playerId) implements CheckpointEvent {
	}

	record PlayPresenceObserved(Set<UUID> playerIds) implements CheckpointEvent {
		public PlayPresenceObserved {
			playerIds = Set.copyOf(playerIds);
		}
	}

	record MarkReady(UUID playerId, UUID expectedEpochId, long expectedRosterVersion, long nowNanos) implements CheckpointEvent {
	}

	record WithdrawReady(UUID playerId, UUID expectedEpochId) implements CheckpointEvent {
	}

	record Tick(long nowNanos) implements CheckpointEvent {
	}

	record CheckpointRequested() implements CheckpointEvent {
	}

	record SavingStarted() implements CheckpointEvent {
	}

	record CheckpointSucceeded(long nowNanos, boolean resumeImmediately) implements CheckpointEvent {
		public CheckpointSucceeded(long nowNanos) {
			this(nowNanos, false);
		}
	}

	record CheckpointFailed() implements CheckpointEvent {
	}

	record DeathCountdownStarted() implements CheckpointEvent {
	}

	record ServerActuallyStopping() implements CheckpointEvent {
	}

	record RollbackSucceeded() implements CheckpointEvent {
	}

	record RollbackFailed() implements CheckpointEvent {
	}
}
