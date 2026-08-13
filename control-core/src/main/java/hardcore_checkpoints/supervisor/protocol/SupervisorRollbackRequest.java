package hardcore_checkpoints.supervisor.protocol;

import hardcore_checkpoints.recovery.DeathTransaction;

import java.util.Objects;
import java.util.UUID;

public record SupervisorRollbackRequest(
		int schemaVersion,
		UUID sessionId,
		UUID deathTransactionId,
		UUID worldLineageId,
		UUID worldInstanceId,
		UUID activationId,
		UUID checkpointId
) {
	public SupervisorRollbackRequest {
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("Unsupported supervisor rollback schema: " + schemaVersion);
		}
		Objects.requireNonNull(sessionId, "sessionId");
		Objects.requireNonNull(deathTransactionId, "deathTransactionId");
		Objects.requireNonNull(worldLineageId, "worldLineageId");
		Objects.requireNonNull(worldInstanceId, "worldInstanceId");
		Objects.requireNonNull(activationId, "activationId");
		Objects.requireNonNull(checkpointId, "checkpointId");
	}

	public static SupervisorRollbackRequest create(UUID sessionId, DeathTransaction transaction) {
		return new SupervisorRollbackRequest(
				1,
				sessionId,
				transaction.transactionId(),
				transaction.worldLineageId(),
				transaction.worldInstanceId(),
				transaction.activationId(),
				transaction.checkpointId()
		);
	}

	public boolean matches(DeathTransaction transaction) {
		return deathTransactionId.equals(transaction.transactionId())
				&& worldLineageId.equals(transaction.worldLineageId())
				&& worldInstanceId.equals(transaction.worldInstanceId())
				&& activationId.equals(transaction.activationId())
				&& checkpointId.equals(transaction.checkpointId());
	}
}
