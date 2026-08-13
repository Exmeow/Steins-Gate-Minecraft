package hardcore_checkpoints.recovery;

import com.google.gson.JsonObject;
import hardcore_checkpoints.control.journal.TransactionJournal;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RollbackJournal(
		UUID journalId,
		UUID worldLineageId,
		UUID worldInstanceId,
		UUID activationId,
		UUID deathTransactionId,
		UUID checkpointId,
		String worldDirectoryName,
		String rescueDirectoryName,
		String stagingDirectoryName,
		RollbackFilesystemStage stage,
		String createdAtUtc,
		String updatedAtUtc
) {
	public static final String TRANSACTION_TYPE = "ROLLBACK_FILESYSTEM";

	public RollbackJournal {
		Objects.requireNonNull(journalId, "journalId");
		Objects.requireNonNull(worldLineageId, "worldLineageId");
		Objects.requireNonNull(worldInstanceId, "worldInstanceId");
		Objects.requireNonNull(activationId, "activationId");
		Objects.requireNonNull(deathTransactionId, "deathTransactionId");
		Objects.requireNonNull(checkpointId, "checkpointId");
		validateFileName(worldDirectoryName, "worldDirectoryName");
		validateFileName(rescueDirectoryName, "rescueDirectoryName");
		validateFileName(stagingDirectoryName, "stagingDirectoryName");
		Objects.requireNonNull(stage, "stage");
		Instant.parse(createdAtUtc);
		Instant.parse(updatedAtUtc);
	}

	public static RollbackJournal prepare(DeathTransaction death, Path worldRoot, Instant now) {
		Objects.requireNonNull(death, "death");
		String worldName = Objects.requireNonNull(worldRoot.getFileName(), "World root must have a name").toString();
		String suffix = death.transactionId().toString();
		return new RollbackJournal(
				UUID.randomUUID(),
				death.worldLineageId(),
				death.worldInstanceId(),
				death.activationId(),
				death.transactionId(),
				death.checkpointId(),
				worldName,
				worldName + ".pre-rollback." + suffix,
				worldName + ".restoring." + suffix,
				RollbackFilesystemStage.PREPARED,
				now.toString(),
				now.toString()
		);
	}

	public RollbackJournal transitionTo(RollbackFilesystemStage target, Instant now) {
		Objects.requireNonNull(target, "target");
		if (target.ordinal() != stage.ordinal() + 1) {
			throw new IllegalStateException("Invalid rollback journal transition: " + stage + " -> " + target);
		}
		return new RollbackJournal(
				journalId,
				worldLineageId,
				worldInstanceId,
				activationId,
				deathTransactionId,
				checkpointId,
				worldDirectoryName,
				rescueDirectoryName,
				stagingDirectoryName,
				target,
				createdAtUtc,
				now.toString()
		);
	}

	public boolean belongsTo(DeathTransaction death) {
		return deathTransactionId.equals(death.transactionId())
				&& checkpointId.equals(death.checkpointId())
				&& worldLineageId.equals(death.worldLineageId())
				&& worldInstanceId.equals(death.worldInstanceId())
				&& activationId.equals(death.activationId());
	}

	public TransactionJournal toTransactionJournal() {
		JsonObject payload = new JsonObject();
		payload.addProperty("deathTransactionId", deathTransactionId.toString());
		payload.addProperty("checkpointId", checkpointId.toString());
		payload.addProperty("worldDirectoryName", worldDirectoryName);
		payload.addProperty("rescueDirectoryName", rescueDirectoryName);
		payload.addProperty("stagingDirectoryName", stagingDirectoryName);
		payload.addProperty("createdAtUtc", createdAtUtc);
		return new TransactionJournal(
				1,
				journalId,
				worldLineageId,
				worldInstanceId,
				activationId,
				TRANSACTION_TYPE,
				stage.name(),
				stage == RollbackFilesystemStage.HEALTH_VERIFIED,
				payload,
				updatedAtUtc
		);
	}

	public static RollbackJournal fromTransactionJournal(TransactionJournal journal) {
		if (!TRANSACTION_TYPE.equals(journal.transactionType())) {
			throw new IllegalArgumentException("Not a rollback filesystem journal");
		}
		JsonObject payload = journal.payload();
		RollbackFilesystemStage stage = RollbackFilesystemStage.valueOf(journal.stage());
		RollbackJournal result = new RollbackJournal(
				journal.transactionId(),
				journal.worldLineageId(),
				journal.worldInstanceId(),
				Objects.requireNonNull(journal.activationId(), "activationId"),
				UUID.fromString(requiredString(payload, "deathTransactionId")),
				UUID.fromString(requiredString(payload, "checkpointId")),
				requiredString(payload, "worldDirectoryName"),
				requiredString(payload, "rescueDirectoryName"),
				requiredString(payload, "stagingDirectoryName"),
				stage,
				requiredString(payload, "createdAtUtc"),
				journal.updatedAtUtc()
		);
		if (journal.committed() != (stage == RollbackFilesystemStage.HEALTH_VERIFIED)) {
			throw new IllegalArgumentException("Rollback journal committed flag does not match its stage");
		}
		return result;
	}

	private static String requiredString(JsonObject payload, String name) {
		if (!payload.has(name) || payload.get(name).isJsonNull()) {
			throw new IllegalArgumentException("Missing rollback journal property: " + name);
		}
		return payload.get(name).getAsString();
	}

	private static void validateFileName(String value, String field) {
		Objects.requireNonNull(value, field);
		Path path = Path.of(value);
		if (value.isBlank() || path.isAbsolute() || path.getNameCount() != 1 || value.equals(".") || value.equals("..")) {
			throw new IllegalArgumentException("Invalid " + field + ": " + value);
		}
	}
}
