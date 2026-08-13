package hardcore_checkpoints.recovery;

import com.google.gson.JsonObject;
import hardcore_checkpoints.control.WorldIdentity;
import hardcore_checkpoints.control.journal.TransactionJournal;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;

public record DeathTransaction(
		int ruleVersion,
		UUID transactionId,
		UUID worldLineageId,
		UUID worldInstanceId,
		UUID activationId,
		UUID checkpointId,
		UUID firstDeathPlayerId,
		DeathTransactionStage stage,
		String createdAtUtc,
		String countdownDeadlineUtc,
		String updatedAtUtc,
		String failureCode,
		String failureMessage
) {
	public static final int CURRENT_RULE_VERSION = 1;
	public static final String TRANSACTION_TYPE = "DEATH_ROLLBACK";
	public static final Duration COUNTDOWN_DURATION = Duration.ofSeconds(20);

	public DeathTransaction {
		if (ruleVersion != CURRENT_RULE_VERSION) {
			throw new IllegalArgumentException("Unsupported death transaction rule version: " + ruleVersion);
		}
		Objects.requireNonNull(transactionId, "transactionId");
		Objects.requireNonNull(worldLineageId, "worldLineageId");
		Objects.requireNonNull(worldInstanceId, "worldInstanceId");
		Objects.requireNonNull(activationId, "activationId");
		Objects.requireNonNull(checkpointId, "checkpointId");
		Objects.requireNonNull(firstDeathPlayerId, "firstDeathPlayerId");
		Objects.requireNonNull(stage, "stage");
		parseInstant(createdAtUtc, "createdAtUtc");
		parseInstant(countdownDeadlineUtc, "countdownDeadlineUtc");
		parseInstant(updatedAtUtc, "updatedAtUtc");
		if ((failureCode == null) != (failureMessage == null)) {
			throw new IllegalArgumentException("Failure code and message must either both be present or both be absent");
		}
		if (stage == DeathTransactionStage.RECOVERY_FAILED && failureCode == null) {
			throw new IllegalArgumentException("A failed recovery must include failure details");
		}
		if (stage != DeathTransactionStage.RECOVERY_FAILED && failureCode != null) {
			throw new IllegalArgumentException("Failure details are only valid in RECOVERY_FAILED");
		}
	}

	public static DeathTransaction start(
			WorldIdentity identity,
			UUID checkpointId,
			UUID firstDeathPlayerId,
			Instant now
	) {
		Objects.requireNonNull(identity, "identity");
		Objects.requireNonNull(now, "now");
		if (identity.activationId() == null) {
			throw new IllegalArgumentException("Death transactions require an active activation");
		}
		String created = now.toString();
		return new DeathTransaction(
				CURRENT_RULE_VERSION,
				UUID.randomUUID(),
				identity.worldLineageId(),
				identity.worldInstanceId(),
				identity.activationId(),
				checkpointId,
				firstDeathPlayerId,
				DeathTransactionStage.COUNTDOWN,
				created,
				now.plus(COUNTDOWN_DURATION).toString(),
				created,
				null,
				null
		);
	}

	public DeathTransaction transitionTo(DeathTransactionStage target, Instant now) {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(now, "now");
		if (!isAllowedTransition(stage, target)) {
			throw new IllegalStateException("Invalid death transaction transition: " + stage + " -> " + target);
		}
		return copy(target, now, null, null);
	}

	public DeathTransaction fail(String code, String message, Instant now) {
		if (stage != DeathTransactionStage.RESTORING) {
			throw new IllegalStateException("Only an active restore can fail");
		}
		if (code == null || code.isBlank() || message == null || message.isBlank()) {
			throw new IllegalArgumentException("Recovery failure details must not be blank");
		}
		return copy(DeathTransactionStage.RECOVERY_FAILED, now, code, message);
	}

	public Instant countdownDeadline() {
		return Instant.parse(countdownDeadlineUtc);
	}

	public boolean belongsTo(WorldIdentity identity) {
		return worldLineageId.equals(identity.worldLineageId())
				&& worldInstanceId.equals(identity.worldInstanceId())
				&& activationId.equals(identity.activationId());
	}

	public TransactionJournal toJournal() {
		JsonObject payload = new JsonObject();
		payload.addProperty("ruleVersion", ruleVersion);
		payload.addProperty("checkpointId", checkpointId.toString());
		payload.addProperty("firstDeathPlayerId", firstDeathPlayerId.toString());
		payload.addProperty("createdAtUtc", createdAtUtc);
		payload.addProperty("countdownDeadlineUtc", countdownDeadlineUtc);
		if (failureCode != null) {
			payload.addProperty("failureCode", failureCode);
			payload.addProperty("failureMessage", failureMessage);
		}
		return new TransactionJournal(
				1,
				transactionId,
				worldLineageId,
				worldInstanceId,
				activationId,
				TRANSACTION_TYPE,
				stage.name(),
				stage == DeathTransactionStage.RECOVERED,
				payload,
				updatedAtUtc
		);
	}

	public static DeathTransaction fromJournal(TransactionJournal journal) {
		Objects.requireNonNull(journal, "journal");
		if (!TRANSACTION_TYPE.equals(journal.transactionType())) {
			throw new IllegalArgumentException("Not a death rollback transaction: " + journal.transactionType());
		}
		if (journal.activationId() == null) {
			throw new IllegalArgumentException("Death transaction is missing activation identity");
		}
		JsonObject payload = journal.payload();
		try {
			DeathTransactionStage stage = DeathTransactionStage.valueOf(journal.stage());
			String failureCode = optionalString(payload, "failureCode");
			String failureMessage = optionalString(payload, "failureMessage");
			DeathTransaction result = new DeathTransaction(
					requiredInt(payload, "ruleVersion"),
					journal.transactionId(),
					journal.worldLineageId(),
					journal.worldInstanceId(),
					journal.activationId(),
					UUID.fromString(requiredString(payload, "checkpointId")),
					UUID.fromString(requiredString(payload, "firstDeathPlayerId")),
					stage,
					requiredString(payload, "createdAtUtc"),
					requiredString(payload, "countdownDeadlineUtc"),
					journal.updatedAtUtc(),
					failureCode,
					failureMessage
			);
			if (journal.committed() != (stage == DeathTransactionStage.RECOVERED)) {
				throw new IllegalArgumentException("Death transaction committed flag does not match its stage");
			}
			return result;
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Invalid death transaction " + journal.transactionId(), exception);
		}
	}

	private DeathTransaction copy(
			DeathTransactionStage target,
			Instant now,
			String nextFailureCode,
			String nextFailureMessage
	) {
		return new DeathTransaction(
				ruleVersion,
				transactionId,
				worldLineageId,
				worldInstanceId,
				activationId,
				checkpointId,
				firstDeathPlayerId,
				target,
				createdAtUtc,
				countdownDeadlineUtc,
				now.toString(),
				nextFailureCode,
				nextFailureMessage
		);
	}

	private static boolean isAllowedTransition(DeathTransactionStage source, DeathTransactionStage target) {
		return switch (source) {
			case COUNTDOWN -> target == DeathTransactionStage.ROLLBACK_PENDING;
			case ROLLBACK_PENDING -> target == DeathTransactionStage.RESTORING;
			case RESTORING -> target == DeathTransactionStage.RECOVERED;
			case RECOVERY_FAILED -> target == DeathTransactionStage.RESTORING;
			case RECOVERED -> false;
		};
	}

	private static String requiredString(JsonObject payload, String property) {
		if (!payload.has(property) || payload.get(property).isJsonNull()) {
			throw new IllegalArgumentException("Missing death transaction property: " + property);
		}
		return payload.get(property).getAsString();
	}

	private static int requiredInt(JsonObject payload, String property) {
		if (!payload.has(property) || payload.get(property).isJsonNull()) {
			throw new IllegalArgumentException("Missing death transaction property: " + property);
		}
		return payload.get(property).getAsInt();
	}

	private static String optionalString(JsonObject payload, String property) {
		return payload.has(property) && !payload.get(property).isJsonNull()
				? payload.get(property).getAsString()
				: null;
	}

	private static Instant parseInstant(String value, String field) {
		Objects.requireNonNull(value, field);
		try {
			return Instant.parse(value);
		} catch (DateTimeParseException exception) {
			throw new IllegalArgumentException("Invalid " + field + ": " + value, exception);
		}
	}
}
