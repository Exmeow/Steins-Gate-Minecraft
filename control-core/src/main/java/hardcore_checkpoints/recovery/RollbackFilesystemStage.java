package hardcore_checkpoints.recovery;

public enum RollbackFilesystemStage {
	PREPARED,
	WORLD_RESCUED,
	SNAPSHOT_STAGED,
	WORLD_PUBLISHED,
	HEALTH_VERIFIED
}
