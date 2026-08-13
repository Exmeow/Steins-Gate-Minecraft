package hardcore_checkpoints.fabric.client;

import hardcore_checkpoints.server.network.payload.ProtocolChallengePayload;
import hardcore_checkpoints.supervisor.protocol.SupervisorStatusResponse;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ServerData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DedicatedRecoveryControllerTest {
	@AfterEach
	void clearTarget() {
		DedicatedRecoveryController.configure(new ProtocolChallengePayload(2, 0L, false, "test", 0, null));
	}

	@Test
	void bindsRecoveryTargetAfterPlayJoin() throws Exception {
		SharedConstants.tryDetectVersion();
		UUID sessionId = UUID.randomUUID();
		DedicatedRecoveryController.configure(new ProtocolChallengePayload(
				2,
				0L,
				true,
				"test",
				25566,
				sessionId
		));

		assertNotNull(field("endpoint"));
		assertNull(field("target"));

		ServerData serverData = new ServerData("Local test", "localhost:25565", ServerData.Type.OTHER);
		DedicatedRecoveryController.enterPlay(serverData);

		Object target = field("target");
		assertNotNull(target);
		assertEquals(serverData, component(target, "serverData"));
		assertEquals("localhost", component(target, "host"));
		assertEquals(25566, component(target, "statusPort"));
		assertEquals(sessionId, component(target, "sessionId"));
	}
	@Test
	void rejectsStaleReadyUntilSupervisorStateAdvances() throws Exception {
		UUID sessionId = UUID.randomUUID();
		setField("recoveryBaselineEpoch", 5L);
		setField("recoveryCycleObserved", false);

		assertFalse(DedicatedRecoveryController.observeRecoveryStatus(status(sessionId, 5L, "READY_TO_CONNECT")));
		assertFalse(DedicatedRecoveryController.observeRecoveryStatus(status(sessionId, 6L, "STOPPING")));
		assertTrue(DedicatedRecoveryController.observeRecoveryStatus(status(sessionId, 7L, "READY_TO_CONNECT")));
	}

	@Test
	void acceptsAdvancedReadyWhenIntermediatePhaseWasMissed() throws Exception {
		UUID sessionId = UUID.randomUUID();
		setField("recoveryBaselineEpoch", 5L);
		setField("recoveryCycleObserved", false);

		assertTrue(DedicatedRecoveryController.observeRecoveryStatus(status(sessionId, 8L, "READY_TO_CONNECT")));
	}

	private static SupervisorStatusResponse status(UUID sessionId, long epoch, String phase) {
		return new SupervisorStatusResponse(
				1,
				sessionId,
				epoch,
				phase,
				1.0,
				"READY_TO_CONNECT".equals(phase),
				null,
				null,
				Instant.now().toString()
		);
	}

	private static void setField(String name, Object value) throws ReflectiveOperationException {
		Field field = DedicatedRecoveryController.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(null, value);
	}

	private static Object field(String name) throws ReflectiveOperationException {
		Field field = DedicatedRecoveryController.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(null);
	}

	private static Object component(Object record, String name) throws ReflectiveOperationException {
		Method method = record.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return method.invoke(record);
	}
}
