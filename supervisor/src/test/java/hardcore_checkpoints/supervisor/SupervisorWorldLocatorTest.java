package hardcore_checkpoints.supervisor;

import hardcore_checkpoints.control.repository.ControlStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
final class SupervisorWorldLocatorTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void locatesTheExternalControlRootWhileTheWorldDirectoryIsTemporarilyMissing() throws Exception {
		Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
		var initialized = new ControlStateRepository().initializeDisabled(world);
		Files.delete(world.resolve("hardcore_checkpoints.identity.json"));
		Files.delete(world);

		var located = new SupervisorWorldLocator().locate(world);
		assertEquals(initialized.layout().instanceRoot(), located.layout().instanceRoot());
		assertEquals(initialized.metadata().identity(), located.metadata().identity());
	}

	@Test
	void initializesDisabledControlForAStoppedMinecraftWorld() throws Exception {
		Path world = Files.createDirectory(temporaryDirectory.resolve("existing-world"));
		Files.write(world.resolve("level.dat"), new byte[]{1});

		var located = new SupervisorWorldLocator().locate(world);
		assertFalse(located.metadata().featureEnabled());
		assertEquals(world.toAbsolutePath().normalize(), located.layout().worldRoot());
	}

	@Test
	void rejectsAnEmptyWorldDirectory() throws Exception {
		Path world = Files.createDirectory(temporaryDirectory.resolve("empty-world"));
		assertThrows(IOException.class, () -> new SupervisorWorldLocator().locate(world));
	}

	@Test
	void rejectsAnUninitializedWorldWithAnOwnedSessionLock() throws Exception {
		Path world = Files.createDirectory(temporaryDirectory.resolve("running-world"));
		Files.write(world.resolve("level.dat"), new byte[]{1});
		try (FileChannel channel = FileChannel.open(
				world.resolve("session.lock"),
				StandardOpenOption.CREATE,
				StandardOpenOption.WRITE
		); var ignored = channel.lock()) {
			assertThrows(IOException.class, () -> new SupervisorWorldLocator().locate(world));
		}
	}
}
