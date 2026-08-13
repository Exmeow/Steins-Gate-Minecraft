package hardcore_checkpoints.supervisor;

import hardcore_checkpoints.supervisor.protocol.SupervisorStatusResponse;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class SupervisorState {
	private final UUID sessionId;
	private final AtomicLong epoch = new AtomicLong();
	private final AtomicReference<SupervisorStatusResponse> status;

	SupervisorState(UUID sessionId) {
		this.sessionId = sessionId;
		this.status = new AtomicReference<>(new SupervisorStatusResponse(
				1,
				sessionId,
				0,
				SupervisorPhase.STARTING.name(),
				0.0,
				false,
				null,
				null,
				Instant.now().toString()
		));
	}

	SupervisorStatusResponse snapshot() {
		return status.get();
	}

	void update(SupervisorPhase phase, double progress, boolean minecraftRunning) {
		set(phase, progress, minecraftRunning, null, null);
	}

	void fail(String code, String message, boolean minecraftRunning) {
		set(SupervisorPhase.FAILED, 0.0, minecraftRunning, code, message);
	}

	private void set(
			SupervisorPhase phase,
			double progress,
			boolean minecraftRunning,
			String errorCode,
			String errorMessage
	) {
		long nextEpoch = epoch.incrementAndGet();
		status.set(new SupervisorStatusResponse(
				1,
				sessionId,
				nextEpoch,
				phase.name(),
				progress,
				minecraftRunning,
				errorCode,
				errorMessage,
				Instant.now().toString()
		));
	}
}
