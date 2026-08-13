package hardcore_checkpoints.checkpoint;

import hardcore_checkpoints.control.WorldIdentity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CheckpointPointer(
		int schemaVersion,
		WorldIdentity worldIdentity,
		UUID latestCheckpointId,
		String latestManifestChecksum,
		UUID previousCheckpointId,
		String publishedAtUtc
) {
	public CheckpointPointer {
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("Unsupported checkpoint pointer schema: " + schemaVersion);
		}
		Objects.requireNonNull(worldIdentity, "worldIdentity");
		Objects.requireNonNull(latestCheckpointId, "latestCheckpointId");
		Objects.requireNonNull(latestManifestChecksum, "latestManifestChecksum");
		Objects.requireNonNull(publishedAtUtc, "publishedAtUtc");
		if (!latestManifestChecksum.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Invalid manifest checksum");
		}
	}

	public static CheckpointPointer publish(
			WorldIdentity identity,
			UUID latestCheckpointId,
			String manifestChecksum,
			UUID previousCheckpointId
	) {
		return new CheckpointPointer(
				1,
				identity,
				latestCheckpointId,
				manifestChecksum,
				previousCheckpointId,
				Instant.now().toString()
		);
	}
}
