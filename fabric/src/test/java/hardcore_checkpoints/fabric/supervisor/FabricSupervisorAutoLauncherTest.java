package hardcore_checkpoints.fabric.supervisor;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FabricSupervisorAutoLauncherTest {
	@Test
	void preservesTheMinecraftCommandAfterTheSupervisorDelimiter() {
		var config = new SupervisorAutoLaunchConfig(true, "0.0.0.0", 25566, 300);
		var command = FabricSupervisorAutoLauncher.buildSupervisorCommand(
				List.of("C:\\Program Files\\Java\\jdk-21\\bin\\java.exe", "-Xmx6G", "-jar", "fabric server.jar", "nogui"),
				Path.of("D:\\Minecraft Server\\.hardcore_checkpoints\\runtime\\supervisor.jar"),
				Path.of("D:\\Minecraft Server\\world"),
				config,
				1234L
		);

		int delimiter = command.indexOf("--");
		assertTrue(delimiter > 0);
		assertEquals(1234L, Long.parseLong(command.get(delimiter - 1)));
		assertEquals(
				List.of("C:\\Program Files\\Java\\jdk-21\\bin\\java.exe", "-Xmx6G", "-jar", "fabric server.jar", "nogui"),
				command.subList(delimiter + 1, command.size())
		);
	}

	@Test
	void keepsTheOriginalJvmAliveUntilTheSupervisorExits() throws Exception {
		TestProcess supervisor = new TestProcess();
		FabricSupervisorConsoleProxy.activate(supervisor);
		Thread relay = FabricSupervisorAutoLauncher.startLifecycleRelay(supervisor);
		Thread.sleep(100L);
		assertFalse(relay.isDaemon());
		assertTrue(supervisor.isAlive());
		assertTrue(relay.isAlive());
		supervisor.complete(0);
		relay.join(5_000L);
		assertFalse(relay.isAlive());
		assertFalse(supervisor.isAlive());
		assertFalse(FabricSupervisorConsoleProxy.isActive());
	}

	@Test
	void forwardsConsoleLinesIntoSupervisorInput() {
		TestProcess supervisor = new TestProcess();
		FabricSupervisorConsoleProxy.activate(supervisor);
		try {
			assertTrue(FabricSupervisorConsoleProxy.forward("say 你好"));
			assertEquals("say 你好\n", supervisor.consoleInput());
		} finally {
			FabricSupervisorConsoleProxy.release(supervisor);
		}
		assertFalse(FabricSupervisorConsoleProxy.isActive());
	}
	private static final class TestProcess extends Process {
		private final CompletableFuture<Integer> exitCode = new CompletableFuture<>();
		private final ByteArrayOutputStream consoleInput = new ByteArrayOutputStream();

		void complete(int code) {
			exitCode.complete(code);
		}

		String consoleInput() {
			return consoleInput.toString(StandardCharsets.UTF_8);
		}
		@Override
		public OutputStream getOutputStream() {
			return consoleInput;
		}

		@Override
		public InputStream getInputStream() {
			return InputStream.nullInputStream();
		}

		@Override
		public InputStream getErrorStream() {
			return InputStream.nullInputStream();
		}

		@Override
		public int waitFor() throws InterruptedException {
			try {
				return exitCode.get();
			} catch (java.util.concurrent.ExecutionException exception) {
				throw new AssertionError(exception);
			}
		}

		@Override
		public int exitValue() {
			if (!exitCode.isDone()) {
				throw new IllegalThreadStateException();
			}
			return exitCode.join();
		}

		@Override
		public void destroy() {
			complete(143);
		}

		@Override
		public boolean isAlive() {
			return !exitCode.isDone();
		}

		@Override
		public long pid() {
			return 1234L;
		}
	}
}
