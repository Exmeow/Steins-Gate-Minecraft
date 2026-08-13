package hardcore_checkpoints.server.network;

import hardcore_checkpoints.server.state.CheckpointPhase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Phase4ProtocolTest {
	@Test
	void protocolRequiresExactVersionAndAllCapabilities() {
		assertTrue(ControlProtocol.compatible(ControlProtocol.VERSION, ControlProtocol.REQUIRED_CAPABILITIES));
		assertTrue(ControlProtocol.compatible(ControlProtocol.VERSION, ControlProtocol.REQUIRED_CAPABILITIES | (1L << 20)));
		assertFalse(ControlProtocol.compatible(ControlProtocol.VERSION + 1, ControlProtocol.REQUIRED_CAPABILITIES));
		assertFalse(ControlProtocol.compatible(ControlProtocol.VERSION, ControlProtocol.CAP_CONFIGURATION_CONTROL));
	}

	@Test
	void onlyOneHealthySessionMayExistPerAccount() {
		ControlSessionRegistry registry = new ControlSessionRegistry();
		UUID playerId = UUID.randomUUID();
		var first = registry.register(playerId, false, true, 10);
		var duplicate = registry.register(playerId, true, true, 20);

		assertTrue(first.accepted());
		assertFalse(duplicate.accepted());
		assertTrue(registry.validate(playerId, first.session().sessionId()));
		assertFalse(registry.validate(playerId, UUID.randomUUID()));

		registry.close(playerId, first.session().sessionId());
		var replacement = registry.register(playerId, true, true, 30);
		assertTrue(replacement.accepted());
		assertNotEquals(first.session().sessionId(), replacement.session().sessionId());
	}

	@Test
	void staleSessionCannotReplaceCurrentSession() {
		ControlSessionRegistry registry = new ControlSessionRegistry();
		UUID playerId = UUID.randomUUID();
		ControlSession current = registry.register(playerId, false, false, 0).session();
		ControlSession stale = new ControlSession(
				UUID.randomUUID(),
				playerId,
				ConnectionStage.PLAY,
				false,
				false,
				0,
				0
		);
		assertThrows(IllegalStateException.class, () -> registry.update(stale));
		assertTrue(registry.validate(playerId, current.sessionId()));
	}

	@Test
	void controlSnapshotDefensivelyCopiesLists() {
		List<ControlStateSnapshot.RosterEntry> roster = new ArrayList<>();
		ControlStateSnapshot snapshot = new ControlStateSnapshot(
				UUID.randomUUID(),
				CheckpointPhase.PAUSED,
				UUID.randomUUID(),
				1,
				true,
				false,
				false,
				true,
				false,
				0,
				"PAUSED",
				roster,
				List.of()
		);
		roster.add(new ControlStateSnapshot.RosterEntry(UUID.randomUUID(), "late", "PLAY", false));
		assertTrue(snapshot.roster().isEmpty());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.roster().add(
				new ControlStateSnapshot.RosterEntry(UUID.randomUUID(), "x", "PLAY", false)
		));
	}
}
