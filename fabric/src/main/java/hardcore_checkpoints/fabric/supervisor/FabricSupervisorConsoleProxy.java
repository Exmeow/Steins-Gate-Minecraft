package hardcore_checkpoints.fabric.supervisor;

import hardcore_checkpoints.HardcoreCheckpoints;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class FabricSupervisorConsoleProxy {
	private static ProxyState state;

	private FabricSupervisorConsoleProxy() {
	}

	static synchronized void activate(Process supervisor) {
		if (state != null) {
			throw new IllegalStateException("A supervisor console proxy is already active");
		}
		state = new ProxyState(supervisor, supervisor.getOutputStream());
	}

	public static synchronized boolean isActive() {
		return state != null;
	}

	public static synchronized boolean forward(String line) {
		ProxyState current = state;
		if (current == null) {
			return false;
		}
		try {
			current.input().write(line.getBytes(StandardCharsets.UTF_8));
			current.input().write('\n');
			current.input().flush();
		} catch (IOException exception) {
			HardcoreCheckpoints.LOGGER.error(
					"Failed to forward dedicated console input to supervisor pid {}",
					current.supervisor().pid(),
					exception
			);
			release(current.supervisor());
		}
		return true;
	}

	static synchronized void release(Process supervisor) {
		ProxyState current = state;
		if (current == null || current.supervisor() != supervisor) {
			return;
		}
		state = null;
		try {
			current.input().close();
		} catch (IOException exception) {
			HardcoreCheckpoints.LOGGER.warn("Failed to close supervisor console input proxy", exception);
		}
	}

	private record ProxyState(Process supervisor, OutputStream input) {
	}
}
