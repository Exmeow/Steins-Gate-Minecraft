package hardcore_checkpoints.checkpoint;

import hardcore_checkpoints.control.WorldIdentity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CheckpointManifest(
		int schemaVersion,
		UUID checkpointId,
		WorldIdentity worldIdentity,
		String createdAtUtc,
		long totalBytes,
		List<SnapshotFile> files
) {
	public CheckpointManifest {
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("Unsupported checkpoint manifest schema: " + schemaVersion);
		}
		Objects.requireNonNull(checkpointId, "checkpointId");
		Objects.requireNonNull(worldIdentity, "worldIdentity");
		Objects.requireNonNull(createdAtUtc, "createdAtUtc");
		if (totalBytes < 0) {
			throw new IllegalArgumentException("totalBytes must not be negative");
		}
		files = List.copyOf(files);
	}

	public static CheckpointManifest create(
			UUID checkpointId,
			WorldIdentity worldIdentity,
			long totalBytes,
			List<SnapshotFile> files
	) {
		return new CheckpointManifest(1, checkpointId, worldIdentity, Instant.now().toString(), totalBytes, files);
	}

	public record SnapshotFile(String relativePath, long size, String sha256) {
		public SnapshotFile {
			Objects.requireNonNull(relativePath, "relativePath");
			Objects.requireNonNull(sha256, "sha256");
			if (relativePath.isBlank() || relativePath.startsWith("/") || relativePath.contains("\\")
					|| relativePath.contains("../") || relativePath.equals("..")) {
				throw new IllegalArgumentException("Invalid snapshot path: " + relativePath);
			}
			if (size < 0 || !sha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Invalid snapshot metadata for " + relativePath);
			}
		}
	}
}
