package hardcore_checkpoints.fabric.singleplayer;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SingleplayerActivationIntent {
	private static final AtomicBoolean ENABLE_NEXT_CREATED_WORLD = new AtomicBoolean();

	private SingleplayerActivationIntent() {
	}

	public static void setEnabled(boolean enabled) {
		ENABLE_NEXT_CREATED_WORLD.set(enabled);
	}

	public static boolean consume() {
		return ENABLE_NEXT_CREATED_WORLD.getAndSet(false);
	}

	public static void clear() {
		ENABLE_NEXT_CREATED_WORLD.set(false);
	}
}
