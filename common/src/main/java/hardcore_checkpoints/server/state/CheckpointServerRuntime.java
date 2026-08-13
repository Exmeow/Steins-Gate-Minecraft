package hardcore_checkpoints.server.state;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class CheckpointServerRuntime {
	private static final AtomicReference<CheckpointStateCoordinator> COORDINATOR = new AtomicReference<>();

	private CheckpointServerRuntime() {
	}

	public static void install(CheckpointStateCoordinator coordinator) {
		COORDINATOR.set(coordinator);
	}

	public static Optional<CheckpointStateCoordinator> coordinator() {
		return Optional.ofNullable(COORDINATOR.get());
	}

	public static void clear() {
		COORDINATOR.set(null);
	}
}
