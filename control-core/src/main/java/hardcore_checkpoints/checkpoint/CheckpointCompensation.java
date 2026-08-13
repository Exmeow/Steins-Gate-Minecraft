package hardcore_checkpoints.checkpoint;

import hardcore_checkpoints.control.WorldIdentity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CheckpointCompensation(
		int schemaVersion,
		UUID transactionId,
		WorldIdentity worldIdentity,
		String createdAtUtc,
		List<RecorderState> recorders
) {
	public CheckpointCompensation {
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("Unsupported compensation schema: " + schemaVersion);
		}
		Objects.requireNonNull(transactionId, "transactionId");
		Objects.requireNonNull(worldIdentity, "worldIdentity");
		Objects.requireNonNull(createdAtUtc, "createdAtUtc");
		recorders = List.copyOf(recorders);
	}

	public static CheckpointCompensation create(
			WorldIdentity identity,
			UUID checkpointId,
			List<RecorderState> recorders
	) {
		return new CheckpointCompensation(1, checkpointId, identity, Instant.now().toString(), recorders);
	}

	public record RecorderState(
			String dimension,
			int x,
			int y,
			int z,
			String blockStateSnbt,
			String blockEntitySnbt
	) {
		public RecorderState {
			Objects.requireNonNull(dimension, "dimension");
			Objects.requireNonNull(blockStateSnbt, "blockStateSnbt");
			Objects.requireNonNull(blockEntitySnbt, "blockEntitySnbt");
		}
	}
}
