package hardcore_checkpoints.control.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FileLockWorldActivityProbeTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void detectsAnOwnedMinecraftSessionLock() throws Exception {
		Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
		FileLockWorldActivityProbe probe = new FileLockWorldActivityProbe();
		assertFalse(probe.isWorldRunning(world));

		try (FileChannel channel = FileChannel.open(
				world.resolve("session.lock"),
				StandardOpenOption.CREATE,
				StandardOpenOption.WRITE
		); var ignored = channel.lock()) {
			assertTrue(probe.isWorldRunning(world));
		}
	}
}
