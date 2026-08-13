package hardcore_checkpoints.supervisor;

import hardcore_checkpoints.supervisor.protocol.SupervisorHealthReport;
import hardcore_checkpoints.supervisor.protocol.SupervisorStatusResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SupervisorProtocolTest {
	@Test
	void healthReportSupportsInitialCheckpointAndDisabledRestartBoundaries() {
		UUID session = UUID.randomUUID();
		assertDoesNotThrow(() -> new SupervisorHealthReport(
				1, session, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				true, null, false, true, "WAITING_FOR_ROSTER"
		));
		assertDoesNotThrow(() -> new SupervisorHealthReport(
				1, session, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				false, UUID.randomUUID(), true, false, "FEATURE_DISABLED"
		));
		assertThrows(IllegalArgumentException.class, () -> new SupervisorHealthReport(
				1, session, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				true, UUID.randomUUID(), false, true, "RUNNING"
		));
		assertThrows(IllegalArgumentException.class, () -> new SupervisorHealthReport(
				1, session, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				true, UUID.randomUUID(), true, false, "RUNNING"
		));
	}

	@Test
	void statusRejectsInvalidProgressAndPartialErrors() {
		assertThrows(IllegalArgumentException.class, () -> new SupervisorStatusResponse(
				1, UUID.randomUUID(), 1, "RESTORING", 1.1, false,
				null, null, Instant.now().toString()
		));
		assertThrows(IllegalArgumentException.class, () -> new SupervisorStatusResponse(
				1, UUID.randomUUID(), 1, "FAILED", 0.0, false,
				"FAILED", null, Instant.now().toString()
		));
	}
}
