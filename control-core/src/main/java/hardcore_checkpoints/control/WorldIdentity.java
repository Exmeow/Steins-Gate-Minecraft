package hardcore_checkpoints.control;

import java.util.Objects;
import java.util.UUID;

public record WorldIdentity(UUID worldLineageId, UUID worldInstanceId, UUID activationId) {
	public WorldIdentity {
		Objects.requireNonNull(worldLineageId, "worldLineageId");
		Objects.requireNonNull(worldInstanceId, "worldInstanceId");
	}

	public static WorldIdentity createDisabled() {
		return new WorldIdentity(UUID.randomUUID(), UUID.randomUUID(), null);
	}

	public WorldIdentity withActivationId(UUID newActivationId) {
		return new WorldIdentity(worldLineageId, worldInstanceId, Objects.requireNonNull(newActivationId, "newActivationId"));
	}

	public WorldIdentity withWorldInstanceId(UUID newWorldInstanceId) {
		return new WorldIdentity(worldLineageId, Objects.requireNonNull(newWorldInstanceId, "newWorldInstanceId"), activationId);
	}
}
