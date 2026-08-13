package hardcore_checkpoints.supervisor;

import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.persistence.PathSecurity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Pattern;

final class ObsoleteCycleCleaner {
	private static final System.Logger LOGGER = System.getLogger(ObsoleteCycleCleaner.class.getName());
	private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
	private static final Pattern OWNED_DIRECTORY = Pattern.compile(UUID + "\\." + UUID);

	void start(ControlRootLayout layout) {
		Thread.startVirtualThread(() -> clean(layout));
	}

	private void clean(ControlRootLayout layout) {
		Path obsoleteRoot = layout.obsoleteRoot();
		if (!Files.isDirectory(obsoleteRoot, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (var entries = Files.newDirectoryStream(obsoleteRoot)) {
			for (Path entry : entries) {
				String name = entry.getFileName().toString();
				if (!OWNED_DIRECTORY.matcher(name).matches()
						|| !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
					continue;
				}
				try {
					deleteOwnedTree(obsoleteRoot, entry);
					Thread.sleep(25L);
				} catch (IOException exception) {
					LOGGER.log(System.Logger.Level.WARNING, "Failed to delete obsolete cycle " + entry, exception);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		} catch (IOException exception) {
			LOGGER.log(System.Logger.Level.WARNING, "Failed to enumerate obsolete cycles under " + obsoleteRoot, exception);
		}
	}

	private static void deleteOwnedTree(Path obsoleteRoot, Path entry) throws IOException {
		Path safeRoot = PathSecurity.requireSafeDirectory(obsoleteRoot);
		Path safeEntry = entry.toAbsolutePath().normalize();
		if (!safeRoot.equals(safeEntry.getParent())) {
			throw new IOException("Obsolete cycle is not an immediate child of the ownership directory");
		}
		PathSecurity.requireSafeTree(safeEntry);
		try (var paths = Files.walk(safeEntry)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}
}
