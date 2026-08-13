package hardcore_checkpoints.supervisor;

enum SupervisorPhase {
	STARTING,
	STOPPING,
	RESTORING,
	BOOTING,
	READY_TO_CONNECT,
	STOPPED,
	FAILED
}
