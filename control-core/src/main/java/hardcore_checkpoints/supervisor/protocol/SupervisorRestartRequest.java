package hardcore_checkpoints.supervisor.protocol;

import java.util.Objects;
import java.util.UUID;

public record SupervisorRestartRequest(
		int schemaVersion,
		UUID sessionId,
		Reason reason
) {
	public SupervisorRestartRequest {
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("Unsupported supervisor restart schema: " + schemaVersion);
		}
		Objects.requireNonNull(sessionId, "sessionId");
		Objects.requireNonNull(reason, "reason");
	}

	public enum Reason {
		ENABLE_COMMITTED,
		DISABLE_COMMITTED
	}
}
