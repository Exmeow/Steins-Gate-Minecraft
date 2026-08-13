package hardcore_checkpoints.control.transfer;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class FileLockWorldActivityProbe implements WorldActivityProbe {
	@Override
	public boolean isWorldRunning(Path worldRoot) throws IOException {
		Path normalized = worldRoot.toAbsolutePath().normalize();
		if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
			return false;
		}
		Path lockPath = normalized.resolve("session.lock");
		try (FileChannel channel = FileChannel.open(
				lockPath,
				StandardOpenOption.CREATE,
				StandardOpenOption.WRITE
		)) {
			try (var lock = channel.tryLock()) {
				return lock == null;
			} catch (OverlappingFileLockException exception) {
				return true;
			}
		} catch (java.nio.file.AccessDeniedException exception) {
			return true;
		}
	}
}
