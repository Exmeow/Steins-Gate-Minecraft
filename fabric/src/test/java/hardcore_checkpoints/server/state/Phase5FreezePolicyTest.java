package hardcore_checkpoints.server.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Phase5FreezePolicyTest {
	@Test
	void runningAndDeathCountdownAllowGameplayWhenFeatureIsEnabled() {
		ServerMutationGate gate = new ServerMutationGate();
		for (CheckpointPhase phase : CheckpointPhase.values()) {
			CheckpointServerState state = new CheckpointServerState(
					CheckpointServerState.CURRENT_SCHEMA_VERSION,
					phase,
					0,
					true,
					true,
					RosterState.empty(),
					EpochState.initial(0)
			);
			assertEquals(
					phase == CheckpointPhase.RUNNING || phase == CheckpointPhase.DEATH_COUNTDOWN,
					gate.allowsGameplay(state),
					phase.name()
			);
		}
	}

	@Test
	void disabledFeatureDoesNotClaimGameplayFreeze() {
		assertFalse(CheckpointPhase.FEATURE_DISABLED.isGameplayFrozen());
		assertFalse(new ServerMutationGate().allowsGameplay(CheckpointServerState.featureDisabled()));
		assertTrue(CheckpointPhase.PAUSED.isGameplayFrozen());
		assertFalse(CheckpointPhase.DEATH_COUNTDOWN.isGameplayFrozen());
		assertTrue(CheckpointPhase.SAVING.isGameplayFrozen());
	}
}
