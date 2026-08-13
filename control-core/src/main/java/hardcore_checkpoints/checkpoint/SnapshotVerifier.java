package hardcore_checkpoints.checkpoint;

import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.WorldIdentity;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.control.persistence.PathSecurity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

public final class SnapshotVerifier {
	private final AtomicFileStore fileStore;

	public SnapshotVerifier(AtomicFileStore fileStore) {
		this.fileStore = fileStore;
	}

	public void verify(Path checkpointRoot, CheckpointManifest manifest) throws IOException {
		Path safeCheckpointRoot = PathSecurity.requireSafeDirectory(checkpointRoot);
		verifyWorldSnapshot(safeCheckpointRoot.resolve("world"), manifest);
	}

	public void verifyWorldSnapshot(Path worldSnapshot, CheckpointManifest manifest) throws IOException {
		Path safeWorldSnapshot = PathSecurity.requireSafeDirectory(worldSnapshot);
		PathSecurity.requireSafeTree(safeWorldSnapshot);
		Map<String, CheckpointManifest.SnapshotFile> expected = new HashMap<>();
		long expectedTotal = 0;
		for (CheckpointManifest.SnapshotFile file : manifest.files()) {
			validateRelativePath(file.relativePath());
			if (expected.put(file.relativePath(), file) != null) {
				throw new IOException("Duplicate checkpoint manifest path: " + file.relativePath());
			}
			expectedTotal = Math.addExact(expectedTotal, file.size());
		}
		if (expectedTotal != manifest.totalBytes()) {
			throw new IOException("Checkpoint manifest total byte count is inconsistent");
		}

		Set<String> seen = new HashSet<>();
		try (var paths = Files.walk(safeWorldSnapshot)) {
			for (Path path : paths.sorted().toList()) {
				if (path.equals(safeWorldSnapshot)) {
					continue;
				}
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
					continue;
				}
				String relative = toPortablePath(safeWorldSnapshot.relativize(path));
				CheckpointManifest.SnapshotFile declared = expected.get(relative);
				if (declared == null || !seen.add(relative)) {
					throw new IOException("Unexpected checkpoint snapshot file: " + relative);
				}
				if (Files.size(path) != declared.size() || !hashFile(path).equals(declared.sha256())) {
					throw new IOException("Checkpoint snapshot verification failed: " + relative);
				}
			}
		}
		if (seen.size() != expected.size()) {
			Set<String> missing = new HashSet<>(expected.keySet());
			missing.removeAll(seen);
			throw new IOException("Checkpoint snapshot is missing files: " + missing);
		}
	}

	public CheckpointPointer verifyPublished(ControlRootLayout layout, WorldIdentity expectedIdentity) throws IOException {
		var pointerValue = fileStore.readRequired(layout.instanceRoot().resolve("checkpoint-pointer.json"), CheckpointPointer.class);
		CheckpointPointer pointer = pointerValue.value();
		if (!pointer.worldIdentity().equals(expectedIdentity)) {
			throw new IOException("Checkpoint pointer identity mismatch");
		}
		Path checkpointRoot = layout.checkpointsRoot().resolve(pointer.latestCheckpointId().toString());
		var manifestValue = fileStore.readRequired(checkpointRoot.resolve("manifest.json"), CheckpointManifest.class);
		if (!manifestValue.sha256().equals(pointer.latestManifestChecksum())) {
			throw new IOException("Checkpoint manifest checksum does not match the published pointer");
		}
		CheckpointManifest manifest = manifestValue.value();
		if (!manifest.checkpointId().equals(pointer.latestCheckpointId())
				|| !manifest.worldIdentity().equals(expectedIdentity)) {
			throw new IOException("Published checkpoint manifest identity mismatch");
		}
		verify(checkpointRoot, manifest);
		return pointer;
	}

	static String hashFile(Path path) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
		byte[] buffer = new byte[64 * 1024];
		try (InputStream input = Files.newInputStream(path)) {
			int read;
			while ((read = input.read(buffer)) >= 0) {
				if (read > 0) {
					digest.update(buffer, 0, read);
				}
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	static String toPortablePath(Path path) {
		return path.toString().replace(path.getFileSystem().getSeparator(), "/");
	}

	private static void validateRelativePath(String relative) throws IOException {
		Path path = Path.of(relative).normalize();
		if (path.isAbsolute() || path.getNameCount() == 0 || path.startsWith("..")
				|| !toPortablePath(path).equals(relative)) {
			throw new IOException("Checkpoint manifest path escapes snapshot root: " + relative);
		}
	}
}
