package hardcore_checkpoints.checkpoint;


import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.WorldIdentity;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.control.persistence.AtomicPathMoves;
import hardcore_checkpoints.control.persistence.PathSecurity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public final class CheckpointCaptureService {
	private static final System.Logger LOGGER = System.getLogger(CheckpointCaptureService.class.getName());

	private static final long MINIMUM_FREE_MARGIN = 64L * 1024L * 1024L;
	private static final Pattern PLAYER_DATA_TEMPORARY = Pattern.compile(
			"(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[0-9]+\\.dat"
	);

	private final AtomicFileStore fileStore;
	private final SnapshotVerifier verifier;

	public CheckpointCaptureService(AtomicFileStore fileStore) {
		this.fileStore = fileStore;
		this.verifier = new SnapshotVerifier(fileStore);
	}

	public CapturePlan preflight(ControlRootLayout layout) throws IOException {
		Path worldRoot = PathSecurity.requireSafeDirectory(layout.worldRoot());
		PathSecurity.requireSafeTree(worldRoot);
		PathSecurity.createDirectoriesSecure(layout.instanceRoot(), layout.checkpointsRoot());
		long worldBytes = measureWorld(worldRoot);
		long margin = Math.max(MINIMUM_FREE_MARGIN, worldBytes / 10);
		long requiredBytes = Math.addExact(worldBytes, margin);
		FileStore targetStore = Files.getFileStore(layout.checkpointsRoot());
		long usableBytes = targetStore.getUsableSpace();
		if (usableBytes < requiredBytes) {
			throw new IOException("Insufficient checkpoint space: required=" + requiredBytes + ", usable=" + usableBytes);
		}
		return new CapturePlan(worldBytes, requiredBytes, usableBytes);
	}

	public CaptureResult capture(ControlRootLayout layout, WorldIdentity identity, UUID checkpointId) throws IOException {
		Path worldRoot = PathSecurity.requireSafeDirectory(layout.worldRoot());
		Path checkpointsRoot = PathSecurity.requireSafeDirectory(layout.checkpointsRoot());
		Path temporaryRoot = checkpointsRoot.resolve(".creating." + checkpointId).normalize();
		Path finalRoot = checkpointsRoot.resolve(checkpointId.toString()).normalize();
		if (!temporaryRoot.startsWith(checkpointsRoot) || !finalRoot.startsWith(checkpointsRoot)) {
			throw new IOException("Checkpoint path escaped the checkpoint root");
		}
		if (Files.exists(temporaryRoot, LinkOption.NOFOLLOW_LINKS) || Files.exists(finalRoot, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Checkpoint transaction path already exists for " + checkpointId);
		}

		try {
			Files.createDirectory(temporaryRoot);
			Path temporaryWorld = temporaryRoot.resolve("world");
			Files.createDirectory(temporaryWorld);
			List<CheckpointManifest.SnapshotFile> files = copyWorld(worldRoot, temporaryWorld);
			long totalBytes = files.stream().mapToLong(CheckpointManifest.SnapshotFile::size).sum();
			CheckpointManifest manifest = CheckpointManifest.create(checkpointId, identity, totalBytes, files);
			String manifestChecksum = fileStore.write(temporaryRoot.resolve("manifest.json"), manifest);
			verifier.verify(temporaryRoot, manifest);
			moveAtomically(temporaryRoot, finalRoot);
			flushDirectory(checkpointsRoot);

			CheckpointPointer oldPointer = fileStore.readOptional(
					layout.instanceRoot().resolve("checkpoint-pointer.json"),
					CheckpointPointer.class
			).map(AtomicFileStore.StoredValue::value).orElse(null);
			UUID previousId = oldPointer == null ? null : oldPointer.latestCheckpointId();
			CheckpointPointer pointer = CheckpointPointer.publish(identity, checkpointId, manifestChecksum, previousId);
			fileStore.write(layout.instanceRoot().resolve("checkpoint-pointer.json"), pointer);
			verifier.verifyPublished(layout, identity);

			if (oldPointer != null && oldPointer.previousCheckpointId() != null
					&& !oldPointer.previousCheckpointId().equals(previousId)) {
				try {
					deleteOwnedTree(checkpointsRoot, checkpointsRoot.resolve(oldPointer.previousCheckpointId().toString()));
				} catch (IOException ignored) {
					// The pointer already limits eligibility to latest and previous; stale cleanup can retry later.
				}
			}
			return new CaptureResult(pointer, manifest);
		} catch (IOException | RuntimeException exception) {
			if (Files.exists(temporaryRoot, LinkOption.NOFOLLOW_LINKS)) {
				try {
					deleteOwnedTree(checkpointsRoot, temporaryRoot);
				} catch (IOException cleanupFailure) {
					exception.addSuppressed(cleanupFailure);
				}
			}
			throw exception;
		}
	}

	private static long measureWorld(Path worldRoot) throws IOException {
		long total = 0;
		try (var paths = Files.walk(worldRoot)) {
			for (Path path : paths.toList()) {
				if (excluded(worldRoot, path)) {
					continue;
				}
				try {
					BasicFileAttributes attributes = Files.readAttributes(
							path,
							BasicFileAttributes.class,
							LinkOption.NOFOLLOW_LINKS
					);
					if (attributes.isRegularFile()) {
						total = Math.addExact(total, attributes.size());
					}
				} catch (NoSuchFileException ignored) {
					// Minecraft atomically replaces save files; a temporary path can disappear after enumeration.
				}
			}
		}
		return total;
	}

	private static List<CheckpointManifest.SnapshotFile> copyWorld(Path sourceRoot, Path targetRoot) throws IOException {
		List<CheckpointManifest.SnapshotFile> manifestFiles = new ArrayList<>();
		try (var paths = Files.walk(sourceRoot)) {
			for (Path source : paths.sorted().toList()) {
				if (source.equals(sourceRoot) || excluded(sourceRoot, source)) {
					continue;
				}
				Path relative = sourceRoot.relativize(source);
				Path target = targetRoot.resolve(relative).normalize();
				if (!target.startsWith(targetRoot)) {
					throw new IOException("World snapshot path escaped target root: " + relative);
				}
				BasicFileAttributes attributes;
				try {
					attributes = Files.readAttributes(
							source,
							BasicFileAttributes.class,
							LinkOption.NOFOLLOW_LINKS
					);
				} catch (NoSuchFileException ignored) {
					LOGGER.log(System.Logger.Level.DEBUG, "Skipped vanished world-save path during checkpoint copy: {0}", source);
					continue;
				}
				if (attributes.isDirectory()) {
					Files.createDirectory(target);
				} else if (attributes.isRegularFile()) {
					CopyDigest digest;
					try {
						digest = copyFile(source, target);
					} catch (NoSuchFileException ignored) {
						Files.deleteIfExists(target);
						LOGGER.log(System.Logger.Level.DEBUG, "Skipped world-save file replaced during checkpoint copy: {0}", source);
						continue;
					}
					manifestFiles.add(new CheckpointManifest.SnapshotFile(
							SnapshotVerifier.toPortablePath(relative),
							digest.size(),
							digest.sha256()
					));
				} else {
					throw new IOException("Unsupported world file type: " + source);
				}
			}
		}
		manifestFiles.sort(Comparator.comparing(CheckpointManifest.SnapshotFile::relativePath));
		return List.copyOf(manifestFiles);
	}

	private static CopyDigest copyFile(Path source, Path target) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
		long size = 0;
		byte[] bytes = new byte[64 * 1024];
		try (InputStream input = Files.newInputStream(source);
			 FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			int read;
			while ((read = input.read(bytes)) >= 0) {
				if (read == 0) {
					continue;
				}
				digest.update(bytes, 0, read);
				ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, read);
				while (buffer.hasRemaining()) {
					output.write(buffer);
				}
				size += read;
			}
			output.force(true);
		}
		return new CopyDigest(size, HexFormat.of().formatHex(digest.digest()));
	}

	private static boolean excluded(Path worldRoot, Path path) {
		Path relative = worldRoot.relativize(path);
		if (relative.getNameCount() == 1 && relative.getFileName().toString().equals("session.lock")) {
			return true;
		}
		// PlayerDataStorage.save creates <uuid>-<random>.dat before atomically publishing <uuid>.dat.
		return relative.getNameCount() == 2
				&& relative.getName(0).toString().equals("playerdata")
				&& PLAYER_DATA_TEMPORARY.matcher(relative.getFileName().toString()).matches();
	}

	private static void moveAtomically(Path source, Path target) throws IOException {
		AtomicPathMoves.move(
				source,
				target,
				false,
				"Atomic checkpoint directory publication is not supported"
		);
	}

	private static void deleteOwnedTree(Path ownershipRoot, Path target) throws IOException {
		Path safeOwnershipRoot = PathSecurity.requireSafeDirectory(ownershipRoot);
		Path normalizedTarget = target.toAbsolutePath().normalize();
		if (!normalizedTarget.startsWith(safeOwnershipRoot) || normalizedTarget.equals(safeOwnershipRoot)) {
			throw new IOException("Refusing to delete outside checkpoint ownership root: " + normalizedTarget);
		}
		PathSecurity.requireSafeTree(normalizedTarget);
		try (var paths = Files.walk(normalizedTarget)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static void flushDirectory(Path directory) throws IOException {
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
			channel.force(true);
		} catch (AccessDeniedException | UnsupportedOperationException ignored) {
			// Windows commonly refuses directory handles after an otherwise durable atomic move.
		}
	}

	public record CapturePlan(long worldBytes, long requiredBytes, long usableBytes) {
	}

	public record CaptureResult(CheckpointPointer pointer, CheckpointManifest manifest) {
	}

	private record CopyDigest(long size, String sha256) {
	}
}
