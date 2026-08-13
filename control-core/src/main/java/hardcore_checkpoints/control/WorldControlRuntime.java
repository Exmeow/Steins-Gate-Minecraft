package hardcore_checkpoints.control;

import hardcore_checkpoints.control.repository.WorldControlBootstrap;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class WorldControlRuntime {
	private static final AtomicReference<WorldControlBootstrap.BootstrapResult> CURRENT = new AtomicReference<>();

	private WorldControlRuntime() {
	}

	public static void install(WorldControlBootstrap.BootstrapResult result) {
		CURRENT.set(result);
	}

	public static Optional<WorldControlBootstrap.BootstrapResult> current() {
		return Optional.ofNullable(CURRENT.get());
	}

	public static void clear() {
		CURRENT.set(null);
	}
}
