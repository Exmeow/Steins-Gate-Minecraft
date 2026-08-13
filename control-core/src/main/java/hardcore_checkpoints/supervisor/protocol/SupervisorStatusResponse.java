package hardcore_checkpoints.supervisor.protocol;

import java.util.Objects;
import java.util.UUID;

public record SupervisorStatusResponse(
		int schemaVersion,
		UUID sessionId,
		long epoch,
		String phase,
		double progress,
		boolean minecraftRunning,
		String errorCode,
		String errorMessage,
		String updatedAtUtc
) {
	public SupervisorStatusResponse {
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("Unsupported supervisor status schema: " + schemaVersion);
		}
		Objects.requireNonNull(sessionId, "sessionId");
		Objects.requireNonNull(phase, "phase");
		Objects.requireNonNull(updatedAtUtc, "updatedAtUtc");
		if (progress < 0.0 || progress > 1.0 || Double.isNaN(progress)) {
			throw new IllegalArgumentException("Supervisor progress must be between 0 and 1");
		}
		if ((errorCode == null) != (errorMessage == null)) {
			throw new IllegalArgumentException("Supervisor error code and message must both be present or absent");
		}
	}
}
