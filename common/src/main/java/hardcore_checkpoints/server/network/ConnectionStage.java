package hardcore_checkpoints.server.network;

public enum ConnectionStage {
	CONFIGURING,
	CONTROL_CONNECTED,
	AWAITING_USER_PREPARE,
	READY_TO_JOIN,
	PLAY,
	RECONFIGURING,
	CLOSED
}
