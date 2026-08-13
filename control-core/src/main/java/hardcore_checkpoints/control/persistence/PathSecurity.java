package hardcore_checkpoints.control.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

public final class PathSecurity {
	private PathSecurity() {
	}

	public static Path normalizeAbsolute(Path path) {
		return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
	}

	public static Path requireSafeDirectory(Path path) throws IOException {
		Path normalized = normalizeAbsolute(path);
		inspectExistingComponents(normalized);
		BasicFileAttributes attributes = readAttributes(normalized);
		if (!attributes.isDirectory()) {
			throw new IOException("Expected directory: " + normalized);
		}
		return normalized;
	}

	public static void requireSafeFileOrMissing(Path path) throws IOException {
		Path normalized = normalizeAbsolute(path);
		Path parent = Objects.requireNonNull(normalized.getParent(), "File must have a parent directory");
		inspectExistingComponents(parent);
		if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		BasicFileAttributes attributes = readAttributes(normalized);
		if (!attributes.isRegularFile()) {
			throw new IOException("Expected regular file: " + normalized);
		}
	}

	public static Path resolveInside(Path ownershipRoot, String first, String... more) throws IOException {
		Path root = normalizeAbsolute(ownershipRoot);
		Path candidate = root.resolve(Path.of(first, more)).normalize();
		if (!candidate.startsWith(root)) {
			throw new IOException("Path escapes ownership root: " + candidate);
		}
		return candidate;
	}

	public static void createDirectoriesSecure(Path trustedExistingParent, Path directory) throws IOException {
		Path trustedParent = requireSafeDirectory(trustedExistingParent);
		Path target = normalizeAbsolute(directory);
		if (!target.startsWith(trustedParent)) {
			throw new IOException("Directory escapes trusted parent: " + target);
		}

		Path current = trustedParent;
		for (Path segment : trustedParent.relativize(target)) {
			current = current.resolve(segment);
			if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
				BasicFileAttributes attributes = readAttributes(current);
				if (!attributes.isDirectory()) {
					throw new IOException("Expected directory component: " + current);
				}
			} else {
				Files.createDirectory(current);
			}
		}
	}

	public static void requireSafeTree(Path root) throws IOException {
		Path safeRoot = requireSafeDirectory(root);
		try (var paths = Files.walk(safeRoot)) {
			for (Path path : paths.toList()) {
				try {
					BasicFileAttributes attributes = readAttributes(path);
					if (!attributes.isDirectory() && !attributes.isRegularFile()) {
						throw new IOException("Unsupported file type in owned tree: " + path);
					}
				} catch (NoSuchFileException ignored) {
					// Live world saves can atomically replace a path after Files.walk enumerates it.
				}
			}
		}
	}

	private static void inspectExistingComponents(Path path) throws IOException {
		Path current = path.getRoot();
		if (current == null) {
			throw new IOException("Path is not absolute: " + path);
		}
		for (Path segment : path) {
			current = current.resolve(segment);
			if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
				readAttributes(current);
			}
		}
	}

	private static BasicFileAttributes readAttributes(Path path) throws IOException {
		BasicFileAttributes attributes = Files.readAttributes(
				path,
				BasicFileAttributes.class,
				LinkOption.NOFOLLOW_LINKS
		);
		if (Files.isSymbolicLink(path) || attributes.isSymbolicLink() || attributes.isOther()) {
			throw new IOException("Symbolic links, junctions, reparse points, and special files are not allowed: " + path);
		}
		return attributes;
	}
}
