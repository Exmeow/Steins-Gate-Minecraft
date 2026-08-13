package hardcore_checkpoints.supervisor.protocol;

import hardcore_checkpoints.control.WorldIdentity;

import java.util.Objects;
import java.util.UUID;

public record SupervisorHealthReport(
		int schemaVersion,
		UUID sessionId,
		UUID worldLineageId,
		UUID worldInstanceId,
		UUID activationId,
		boolean featureEnabled,
		UUID checkpointId,
		boolean hasValidCheckpoint,
		boolean gameplayFrozen,
		String serverPhase
) {
	public SupervisorHealthReport {
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("Unsupported supervisor health schema: " + schemaVersion);
		}
		Objects.requireNonNull(sessionId, "sessionId");
		Objects.requireNonNull(worldLineageId, "worldLineageId");
		Objects.requireNonNull(worldInstanceId, "worldInstanceId");
		Objects.requireNonNull(serverPhase, "serverPhase");
		if (featureEnabled && activationId == null) {
			throw new IllegalArgumentException("Enabled health report requires an activation identity");
		}
		if (hasValidCheckpoint != (checkpointId != null)) {
			throw new IllegalArgumentException("Health checkpoint identity does not match hasValidCheckpoint");
		}
		if (featureEnabled && !gameplayFrozen) {
			throw new IllegalArgumentException("Enabled checkpoint worlds must establish the default freeze before health reporting");
		}
	}

	public static SupervisorHealthReport create(
			UUID sessionId,
			WorldIdentity identity,
			boolean featureEnabled,
			UUID checkpointId,
			boolean hasValidCheckpoint,
			boolean gameplayFrozen,
			String serverPhase
	) {
		return new SupervisorHealthReport(
				1,
				sessionId,
				identity.worldLineageId(),
				identity.worldInstanceId(),
				identity.activationId(),
				featureEnabled,
				checkpointId,
				hasValidCheckpoint,
				gameplayFrozen,
				serverPhase
		);
	}
}
