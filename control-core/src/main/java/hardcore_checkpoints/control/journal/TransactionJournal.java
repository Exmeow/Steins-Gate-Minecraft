package hardcore_checkpoints.control.journal;

import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TransactionJournal(
		int schemaVersion,
		UUID transactionId,
		UUID worldLineageId,
		UUID worldInstanceId,
		UUID activationId,
		String transactionType,
		String stage,
		boolean committed,
		JsonObject payload,
		String updatedAtUtc
) {
	public TransactionJournal {
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("Unsupported transaction journal schema: " + schemaVersion);
		}
		Objects.requireNonNull(transactionId, "transactionId");
		Objects.requireNonNull(worldLineageId, "worldLineageId");
		Objects.requireNonNull(worldInstanceId, "worldInstanceId");
		Objects.requireNonNull(transactionType, "transactionType");
		Objects.requireNonNull(stage, "stage");
		Objects.requireNonNull(payload, "payload");
		Objects.requireNonNull(updatedAtUtc, "updatedAtUtc");
	}

	public static TransactionJournal create(
			UUID worldLineageId,
			UUID worldInstanceId,
			UUID activationId,
			String transactionType,
			String initialStage,
			JsonObject payload
	) {
		return new TransactionJournal(
				1,
				UUID.randomUUID(),
				worldLineageId,
				worldInstanceId,
				activationId,
				transactionType,
				initialStage,
				false,
				payload.deepCopy(),
				Instant.now().toString()
		);
	}

	public TransactionJournal rebind(UUID newWorldInstanceId) {
		return new TransactionJournal(
				schemaVersion,
				transactionId,
				worldLineageId,
				newWorldInstanceId,
				activationId,
				transactionType,
				stage,
				committed,
				payload.deepCopy(),
				Instant.now().toString()
		);
	}
}
