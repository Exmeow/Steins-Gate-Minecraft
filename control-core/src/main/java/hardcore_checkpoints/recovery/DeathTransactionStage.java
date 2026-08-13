package hardcore_checkpoints.recovery;

public enum DeathTransactionStage {
	COUNTDOWN,
	ROLLBACK_PENDING,
	RESTORING,
	RECOVERY_FAILED,
	RECOVERED;

	public boolean blocksWorldEntry() {
		return this != RECOVERED;
	}

	public boolean shouldResumeAutomaticallyAfterRestart() {
		return this == COUNTDOWN || this == ROLLBACK_PENDING || this == RESTORING;
	}
}
