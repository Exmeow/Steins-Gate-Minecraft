package hardcore_checkpoints.server.network;

public final class ControlProtocol {
	public static final int VERSION = 2;
	public static final long CAP_CONFIGURATION_CONTROL = 1L;
	public static final long CAP_PLAY_CONTROL = 1L << 1;
	public static final long CAP_FORCED_PAUSE = 1L << 2;
	public static final long CAP_SUPERVISOR_STATUS = 1L << 3;
	public static final long REQUIRED_CAPABILITIES = CAP_CONFIGURATION_CONTROL
			| CAP_PLAY_CONTROL
			| CAP_FORCED_PAUSE
			| CAP_SUPERVISOR_STATUS;

	private ControlProtocol() {
	}

	public static boolean compatible(int version, long capabilities) {
		return version == VERSION && (capabilities & REQUIRED_CAPABILITIES) == REQUIRED_CAPABILITIES;
	}
}
