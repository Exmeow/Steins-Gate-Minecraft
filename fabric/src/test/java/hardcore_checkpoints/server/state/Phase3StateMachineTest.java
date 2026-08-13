package hardcore_checkpoints.server.state;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Phase3StateMachineTest {
	private final CheckpointEventReducer reducer = new CheckpointEventReducer();

	@Test
	void readyBarrierUsesStabilityWindowAndResumeCountdown() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		RosterState roster = new RosterState(4, Set.of(first, second), Set.of());
		CheckpointServerState state = CheckpointServerState.enabled(roster, true);

		state = accept(state, new CheckpointEvent.PlayConnected(first));
		state = accept(state, new CheckpointEvent.PlayConnected(second));
		UUID epochId = state.epoch().epochId();
		state = accept(state, new CheckpointEvent.MarkReady(first, epochId, roster.version(), 1_000));
		state = accept(state, new CheckpointEvent.MarkReady(second, epochId, roster.version(), 1_000));
		assertEquals(CheckpointPhase.START_COUNTDOWN, state.phase());

		state = accept(state, new CheckpointEvent.Tick(1_000 + Duration.ofSeconds(2).toNanos()));
		long deadline = state.epoch().ready().resumeDeadlineNanos();
		assertEquals(CheckpointPhase.START_COUNTDOWN, state.phase());
		assertEquals(1_000 + Duration.ofSeconds(7).toNanos(), deadline);

		state = accept(state, new CheckpointEvent.Tick(deadline));
		assertEquals(CheckpointPhase.RUNNING, state.phase());
	}

	@Test
	void firstReadyBarrierRequestsInitialCheckpoint() {
		UUID player = UUID.randomUUID();
		RosterState roster = new RosterState(1, Set.of(player), Set.of());
		CheckpointServerState state = CheckpointServerState.enabled(roster, false);
		state = accept(state, new CheckpointEvent.PlayConnected(player));
		state = accept(state, new CheckpointEvent.MarkReady(
				player,
				state.epoch().epochId(),
				state.roster().version(),
				10
		));
		assertEquals(CheckpointPhase.CREATING_INITIAL_CHECKPOINT, state.phase());
	}

	@Test
	void disconnectFromRunningCreatesNewPausedEpoch() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		RosterState roster = new RosterState(2, Set.of(first, second), Set.of());
		ReadyState ready = ReadyState.empty()
				.markPlayConnected(first)
				.markPlayConnected(second)
				.markReady(first)
				.markReady(second);
		EpochState epoch = EpochState.initial(roster.version()).withReady(ready);
		CheckpointServerState running = new CheckpointServerState(
				CheckpointServerState.CURRENT_SCHEMA_VERSION,
				CheckpointPhase.RUNNING,
				8,
				true,
				true,
				roster,
				epoch
		);

		CheckpointServerState paused = accept(running, new CheckpointEvent.Disconnected(second));
		assertEquals(CheckpointPhase.PAUSED, paused.phase());
		assertNotEquals(epoch.epochId(), paused.epoch().epochId());
		assertEquals(Set.of(first), paused.epoch().ready().readyMembers());
		assertEquals(Set.of(first), paused.epoch().ready().playConnectedMembers());
	}

	@Test
	void actualPlayPresenceForcesRunningToPauseWhenRosterMemberIsMissing() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		RosterState roster = new RosterState(2, Set.of(first, second), Set.of());
		ReadyState ready = ReadyState.empty()
				.markPlayConnected(first)
				.markPlayConnected(second)
				.markReady(first)
				.markReady(second);
		CheckpointServerState running = new CheckpointServerState(
				CheckpointServerState.CURRENT_SCHEMA_VERSION,
				CheckpointPhase.RUNNING,
				8,
				true,
				true,
				roster,
				EpochState.initial(roster.version()).withReady(ready)
		);

		CheckpointServerState paused = accept(running, new CheckpointEvent.PlayPresenceObserved(Set.of(first)));
		assertEquals(CheckpointPhase.PAUSED, paused.phase());
		assertEquals(Set.of(first), paused.epoch().ready().playConnectedMembers());
		assertEquals(Set.of(first), paused.epoch().ready().readyMembers());
	}

	@Test
	void actualPlayPresenceRepairsRunningEvenWhenStoredPresenceIsAlreadyEmpty() {
		UUID player = UUID.randomUUID();
		RosterState roster = new RosterState(1, Set.of(player), Set.of());
		CheckpointServerState running = new CheckpointServerState(
				CheckpointServerState.CURRENT_SCHEMA_VERSION,
				CheckpointPhase.RUNNING,
				8,
				true,
				true,
				roster,
				EpochState.initial(roster.version())
		);

		assertEquals(
				CheckpointPhase.PAUSED,
				accept(running, new CheckpointEvent.PlayPresenceObserved(Set.of())).phase()
		);
	}

	@Test
	void actualPlayPresenceDoesNotPauseDeathCountdown() {
		UUID player = UUID.randomUUID();
		RosterState roster = new RosterState(1, Set.of(player), Set.of());
		CheckpointServerState countdown = new CheckpointServerState(
				CheckpointServerState.CURRENT_SCHEMA_VERSION,
				CheckpointPhase.DEATH_COUNTDOWN,
				8,
				true,
				true,
				roster,
				EpochState.initial(roster.version()).withReady(ReadyState.empty().markPlayConnected(player))
		);

		CheckpointServerState observed = accept(countdown, new CheckpointEvent.PlayPresenceObserved(Set.of()));
		assertEquals(CheckpointPhase.DEATH_COUNTDOWN, observed.phase());
		assertTrue(observed.epoch().ready().playConnectedMembers().isEmpty());
	}

	@Test
	void deathCountdownDoesNotEnterLogicalPauseOnDisconnect() {
		UUID player = UUID.randomUUID();
		RosterState roster = new RosterState(3, Set.of(player), Set.of());
		CheckpointServerState countdown = new CheckpointServerState(
				CheckpointServerState.CURRENT_SCHEMA_VERSION,
				CheckpointPhase.DEATH_COUNTDOWN,
				2,
				true,
				true,
				roster,
				EpochState.initial(roster.version()).withReady(ReadyState.empty().markPlayConnected(player))
		);

		CheckpointServerState disconnected = accept(countdown, new CheckpointEvent.Disconnected(player));
		assertEquals(CheckpointPhase.DEATH_COUNTDOWN, disconnected.phase());
		assertFalse(disconnected.epoch().ready().playConnectedMembers().contains(player));
		CheckpointServerState rollbackPending = accept(disconnected, new CheckpointEvent.ServerActuallyStopping());
		assertEquals(CheckpointPhase.ROLLBACK_PENDING, rollbackPending.phase());
		assertEquals(
				CheckpointPhase.RECOVERY_FAILED,
				accept(rollbackPending, new CheckpointEvent.RollbackFailed()).phase()
		);
	}

	@Test
	void deathCountdownKeepsGameplayRunningAndRosterChangesInTheSameEpoch() {
		UUID player = UUID.randomUUID();
		UUID pending = UUID.randomUUID();
		RosterState roster = new RosterState(7, Set.of(player), Set.of());
		EpochState epoch = EpochState.initial(roster.version())
				.withReady(ReadyState.empty().markPlayConnected(player).markReady(player));
		CheckpointServerState countdown = new CheckpointServerState(
				CheckpointServerState.CURRENT_SCHEMA_VERSION,
				CheckpointPhase.DEATH_COUNTDOWN,
				4,
				true,
				true,
				roster,
				epoch
		);

		assertFalse(countdown.phase().isGameplayFrozen());
		CheckpointServerState pendingAdded = accept(countdown, new CheckpointEvent.AddRosterMember(
				pending,
				roster.version(),
				UUID.randomUUID(),
				false
		));
		assertEquals(CheckpointPhase.DEATH_COUNTDOWN, pendingAdded.phase());
		assertEquals(epoch.epochId(), pendingAdded.epoch().epochId());
		assertTrue(pendingAdded.roster().pendingAdditions().contains(pending));

		CheckpointServerState removed = accept(pendingAdded, new CheckpointEvent.RemoveRosterMember(
				player,
				pendingAdded.roster().version(),
				UUID.randomUUID()
		));
		assertEquals(CheckpointPhase.DEATH_COUNTDOWN, removed.phase());
		assertEquals(epoch.epochId(), removed.epoch().epochId());
		assertTrue(removed.roster().members().isEmpty());
	}

	@Test
	void staleRosterAndEpochRequestsAreRejected() {
		UUID player = UUID.randomUUID();
		CheckpointServerState state = CheckpointServerState.enabled(RosterState.empty(), false);
		var staleRoster = reducer.reduce(state, new CheckpointEvent.AddRosterMember(
				player,
				state.roster().version() + 1,
				UUID.randomUUID(),
				false
		));
		assertFalse(staleRoster.accepted());
		assertEquals("stale_roster_version", staleRoster.code());

		RosterState roster = new RosterState(1, Set.of(player), Set.of());
		state = CheckpointServerState.enabled(roster, true);
		state = accept(state, new CheckpointEvent.PlayConnected(player));
		var staleEpoch = reducer.reduce(state, new CheckpointEvent.MarkReady(
				player,
				UUID.randomUUID(),
				roster.version(),
				0
		));
		assertFalse(staleEpoch.accepted());
		assertEquals("stale_epoch", staleEpoch.code());
	}

	@Test
	void initialCheckpointSuccessStillUsesResumeBarrier() {
		UUID player = UUID.randomUUID();
		RosterState roster = new RosterState(1, Set.of(player), Set.of());
		ReadyState ready = ReadyState.empty().markPlayConnected(player).markReady(player);
		CheckpointServerState creating = new CheckpointServerState(
				CheckpointServerState.CURRENT_SCHEMA_VERSION,
				CheckpointPhase.CREATING_INITIAL_CHECKPOINT,
				3,
				true,
				false,
				roster,
				EpochState.initial(roster.version()).withReady(ready)
		);
		CheckpointServerState published = accept(creating, new CheckpointEvent.CheckpointSucceeded(50));
		assertEquals(CheckpointPhase.START_COUNTDOWN, published.phase());
		assertTrue(published.hasValidCheckpoint());
		assertEquals(50, published.epoch().ready().stableSinceNanos());
	}

	@Test
	void singleplayerInitialCheckpointSuccessResumesImmediately() {
		UUID player = UUID.randomUUID();
		RosterState roster = new RosterState(1, Set.of(player), Set.of());
		ReadyState ready = ReadyState.empty().markPlayConnected(player).markReady(player);
		CheckpointServerState creating = new CheckpointServerState(
				CheckpointServerState.CURRENT_SCHEMA_VERSION,
				CheckpointPhase.CREATING_INITIAL_CHECKPOINT,
				3,
				true,
				false,
				roster,
				EpochState.initial(roster.version()).withReady(ready)
		);

		CheckpointServerState published = accept(creating, new CheckpointEvent.CheckpointSucceeded(50, true));
		assertEquals(CheckpointPhase.RUNNING, published.phase());
		assertTrue(published.hasValidCheckpoint());
	}

	@Test
	void singleplayerPublicationIgnoresReadyChangesDuringCopy() {
		UUID player = UUID.randomUUID();
		RosterState roster = new RosterState(1, Set.of(player), Set.of());
		CheckpointServerState creating = new CheckpointServerState(
				CheckpointServerState.CURRENT_SCHEMA_VERSION,
				CheckpointPhase.CREATING_INITIAL_CHECKPOINT,
				4,
				true,
				false,
				roster,
				EpochState.initial(roster.version())
		);

		CheckpointServerState published = accept(creating, new CheckpointEvent.CheckpointSucceeded(75, true));
		assertEquals(CheckpointPhase.RUNNING, published.phase());
		assertTrue(published.hasValidCheckpoint());
	}

	@Test
	void savingRetainsTransactionPhaseUntilCompletion() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		RosterState roster = new RosterState(2, Set.of(first, second), Set.of());
		ReadyState ready = ReadyState.empty()
				.markPlayConnected(first)
				.markPlayConnected(second)
				.markReady(first)
				.markReady(second);
		CheckpointServerState saving = new CheckpointServerState(
				CheckpointServerState.CURRENT_SCHEMA_VERSION,
				CheckpointPhase.SAVING,
				4,
				true,
				true,
				roster,
				EpochState.initial(roster.version()).withReady(ready)
		);

		CheckpointServerState disconnected = accept(saving, new CheckpointEvent.Disconnected(second));
		assertEquals(CheckpointPhase.SAVING, disconnected.phase());
		CheckpointServerState completed = accept(disconnected, new CheckpointEvent.CheckpointSucceeded(100));
		assertEquals(CheckpointPhase.WAITING_FOR_PLAYERS, completed.phase());
		assertNotEquals(disconnected.epoch().epochId(), completed.epoch().epochId());
	}

	@Test
	void compensatedSaveFailureReturnsToRunningWhenRosterStayedConnected() {
		UUID player = UUID.randomUUID();
		RosterState roster = new RosterState(1, Set.of(player), Set.of());
		ReadyState ready = ReadyState.empty().markPlayConnected(player).markReady(player);
		CheckpointServerState saving = new CheckpointServerState(
				CheckpointServerState.CURRENT_SCHEMA_VERSION,
				CheckpointPhase.SAVING,
				4,
				true,
				true,
				roster,
				EpochState.initial(roster.version()).withReady(ready)
		);
		assertEquals(CheckpointPhase.RUNNING, accept(saving, new CheckpointEvent.CheckpointFailed()).phase());
	}

	private CheckpointServerState accept(CheckpointServerState state, CheckpointEvent event) {
		var transition = reducer.reduce(state, event);
		assertTrue(transition.accepted(), transition.code());
		return transition.state();
	}
}
