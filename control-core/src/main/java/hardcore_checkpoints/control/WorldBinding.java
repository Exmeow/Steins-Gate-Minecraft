package hardcore_checkpoints.control;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record WorldBinding(
		int schemaVersion,
		UUID worldInstanceId,
		String normalizedWorldPath,
		String updatedAtUtc
) {
	public WorldBinding {
		if (schemaVersion != ControlMetadata.CURRENT_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Unsupported binding schema version: " + schemaVersion);
		}
		Objects.requireNonNull(worldInstanceId, "worldInstanceId");
		Objects.requireNonNull(normalizedWorldPath, "normalizedWorldPath");
		Objects.requireNonNull(updatedAtUtc, "updatedAtUtc");
	}

	public static WorldBinding create(UUID worldInstanceId, String normalizedWorldPath) {
		return new WorldBinding(
				ControlMetadata.CURRENT_SCHEMA_VERSION,
				worldInstanceId,
				normalizedWorldPath,
				Instant.now().toString()
		);
	}
}
