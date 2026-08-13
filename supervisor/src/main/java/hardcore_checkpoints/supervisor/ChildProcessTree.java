package hardcore_checkpoints.supervisor;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ChildProcessTree {
	private ChildProcessTree() {
	}

	static void terminate(Process process, Duration gracefulWait) throws InterruptedException {
		if (process == null || !process.isAlive()) {
			return;
		}
		if (process.waitFor(gracefulWait.toMillis(), TimeUnit.MILLISECONDS)) {
			return;
		}
		List<ProcessHandle> descendants = process.toHandle().descendants()
				.sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
				.toList();
		for (ProcessHandle descendant : descendants) {
			descendant.destroy();
		}
		process.destroy();
		if (process.waitFor(5, TimeUnit.SECONDS)) {
			return;
		}
		for (ProcessHandle descendant : descendants) {
			if (descendant.isAlive()) {
				descendant.destroyForcibly();
			}
		}
		if (process.isAlive()) {
			process.destroyForcibly();
		}
		process.waitFor(10, TimeUnit.SECONDS);
	}
}
