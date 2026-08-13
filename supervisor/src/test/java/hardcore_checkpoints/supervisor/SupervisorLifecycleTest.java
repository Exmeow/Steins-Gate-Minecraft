package hardcore_checkpoints.supervisor;

import hardcore_checkpoints.control.repository.ControlStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SupervisorLifecycleTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void exitsWhenMinecraftStopsNormallyWithoutAControlRequest() throws Exception {
		Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
		Files.write(world.resolve("level.dat"), new byte[]{1});
		new ControlStateRepository().initializeDisabled(world);
		String java = ProcessHandle.current().info().command().orElseThrow();
		String classPath = System.getProperty("java.class.path");
		RunConfiguration configuration = new RunConfiguration(
				world,
				InetAddress.getByName("127.0.0.1"),
				0,
				0,
				Duration.ofSeconds(10),
				false,
				0L,
				List.of(java, "-cp", classPath, ExitZeroChild.class.getName())
		);

		assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
			try (SupervisorEngine engine = new SupervisorEngine(configuration)) {
				engine.run();
			}
		});
	}

	@Test
	void waitsForTheWorldLockWhileThePreviousPidRemainsAlive() throws Exception {
		Path world = Files.createDirectory(temporaryDirectory.resolve("handoff-world"));
		Files.write(world.resolve("level.dat"), new byte[]{1});
		new ControlStateRepository().initializeDisabled(world);
		Path marker = temporaryDirectory.resolve("child-started.txt");
		String java = ProcessHandle.current().info().command().orElseThrow();
		String classPath = System.getProperty("java.class.path");
		Process previous = new ProcessBuilder(
				java, "-cp", classPath, ExitZeroChild.class.getName(), "sleep", "10000"
		).start();
		FileChannel lockChannel = FileChannel.open(
				world.resolve("session.lock"),
				StandardOpenOption.CREATE,
				StandardOpenOption.WRITE
		);
		var worldLock = lockChannel.lock();
		RunConfiguration configuration = new RunConfiguration(
				world,
				InetAddress.getByName("127.0.0.1"),
				0,
				0,
				Duration.ofSeconds(10),
				false,
				previous.pid(),
				List.of(java, "-cp", classPath, ExitZeroChild.class.getName(), "mark", marker.toString())
		);

		assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
			Thread engineThread = Thread.ofPlatform().start(() -> {
				try (SupervisorEngine engine = new SupervisorEngine(configuration)) {
					engine.run();
				} catch (Exception exception) {
					throw new RuntimeException(exception);
				}
			});
			Thread.sleep(300L);
			assertTrue(previous.isAlive());
			assertFalse(Files.exists(marker));
			worldLock.release();
			lockChannel.close();
			engineThread.join(5_000L);
			assertFalse(engineThread.isAlive());
			assertTrue(Files.isRegularFile(marker));
			assertTrue(previous.isAlive());
			previous.destroy();
			assertTrue(previous.waitFor(5, TimeUnit.SECONDS));
		});
	}
}
