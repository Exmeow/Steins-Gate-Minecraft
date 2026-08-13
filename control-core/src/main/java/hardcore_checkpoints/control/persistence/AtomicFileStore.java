package hardcore_checkpoints.control.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

public final class AtomicFileStore {
	private static final int ENVELOPE_FORMAT_VERSION = 1;

	private final Gson gson;

	public AtomicFileStore() {
		this(new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create());
	}

	public AtomicFileStore(Gson gson) {
		this.gson = gson;
	}

	public <T> String write(Path target, T value) throws IOException {
		Path normalizedTarget = PathSecurity.normalizeAbsolute(target);
		Path parent = normalizedTarget.getParent();
		if (parent == null) {
			throw new IOException("Atomic target must have a parent directory: " + normalizedTarget);
		}
		PathSecurity.requireSafeDirectory(parent);
		PathSecurity.requireSafeFileOrMissing(normalizedTarget);
		recover(normalizedTarget);

		String payloadJson = gson.toJson(value);
		String checksum = sha256(payloadJson.getBytes(StandardCharsets.UTF_8));
		Envelope envelope = new Envelope(ENVELOPE_FORMAT_VERSION, checksum, payloadJson);
		byte[] bytes = (gson.toJson(envelope) + "\n").getBytes(StandardCharsets.UTF_8);
		Path temporary = parent.resolve(normalizedTarget.getFileName() + ".tmp." + UUID.randomUUID());
		PathSecurity.requireSafeFileOrMissing(temporary);

		try {
			writeAndFlush(temporary, bytes);
			readEnvelope(temporary);
			atomicReplace(temporary, normalizedTarget);
			flushDirectory(parent);
			return checksum;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	public <T> StoredValue<T> readRequired(Path target, Class<T> type) throws IOException {
		Path normalizedTarget = PathSecurity.normalizeAbsolute(target);
		recover(normalizedTarget);
		if (!Files.isRegularFile(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Required control file is missing: " + normalizedTarget);
		}
		Envelope envelope = readEnvelope(normalizedTarget);
		try {
			T value = gson.fromJson(envelope.payloadJson(), type);
			if (value == null) {
				throw new IOException("Control file contains a null payload: " + normalizedTarget);
			}
			return new StoredValue<>(value, envelope.sha256());
		} catch (JsonParseException | IllegalArgumentException exception) {
			throw new IOException("Invalid control payload in " + normalizedTarget, exception);
		}
	}

	public <T> Optional<StoredValue<T>> readOptional(Path target, Class<T> type) throws IOException {
		Path normalizedTarget = PathSecurity.normalizeAbsolute(target);
		recover(normalizedTarget);
		if (!Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
			return Optional.empty();
		}
		return Optional.of(readRequired(normalizedTarget, type));
	}

	public void recover(Path target) throws IOException {
		Path normalizedTarget = PathSecurity.normalizeAbsolute(target);
		Path parent = normalizedTarget.getParent();
		if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		PathSecurity.requireSafeDirectory(parent);

		boolean targetValid = false;
		if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
			PathSecurity.requireSafeFileOrMissing(normalizedTarget);
			try {
				readEnvelope(normalizedTarget);
				targetValid = true;
			} catch (IOException ignored) {
				// A valid flushed temporary file may still recover the interrupted replacement.
			}
		}

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(
				parent,
				normalizedTarget.getFileName() + ".tmp.*"
		)) {
			var temporaryFiles = new java.util.ArrayList<Path>();
			for (Path temporary : stream) {
				PathSecurity.requireSafeFileOrMissing(temporary);
				temporaryFiles.add(temporary);
			}
			temporaryFiles.sort(Comparator.comparingLong(this::lastModifiedMillis).reversed());

			if (!targetValid) {
				for (Path temporary : temporaryFiles) {
					try {
						readEnvelope(temporary);
						atomicReplace(temporary, normalizedTarget);
						flushDirectory(parent);
						targetValid = true;
						break;
					} catch (IOException ignored) {
						// Invalid temporary files are discarded below.
					}
				}
			}

			for (Path temporary : temporaryFiles) {
				Files.deleteIfExists(temporary);
			}
		}

		if (!targetValid && Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Control file checksum validation failed and no recovery file exists: " + normalizedTarget);
		}
	}

	private Envelope readEnvelope(Path path) throws IOException {
		PathSecurity.requireSafeFileOrMissing(path);
		try {
			Envelope envelope = gson.fromJson(Files.readString(path, StandardCharsets.UTF_8), Envelope.class);
			if (envelope == null || envelope.formatVersion() != ENVELOPE_FORMAT_VERSION) {
				throw new IOException("Unsupported or missing atomic envelope in " + path);
			}
			if (envelope.payloadJson() == null || envelope.sha256() == null) {
				throw new IOException("Incomplete atomic envelope in " + path);
			}
			String actualChecksum = sha256(envelope.payloadJson().getBytes(StandardCharsets.UTF_8));
			if (!MessageDigest.isEqual(
					envelope.sha256().getBytes(StandardCharsets.US_ASCII),
					actualChecksum.getBytes(StandardCharsets.US_ASCII)
			)) {
				throw new IOException("Checksum mismatch in " + path);
			}
			return envelope;
		} catch (JsonParseException exception) {
			throw new IOException("Invalid atomic envelope in " + path, exception);
		}
	}

	private static void writeAndFlush(Path path, byte[] bytes) throws IOException {
		try (FileChannel channel = FileChannel.open(
				path,
				StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE
		)) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
			channel.force(true);
		}
	}

	private static void atomicReplace(Path source, Path target) throws IOException {
		AtomicPathMoves.move(
				source,
				target,
				true,
				"Filesystem does not support required atomic replacement for " + target
		);
	}

	private static void flushDirectory(Path directory) throws IOException {
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
			channel.force(true);
		} catch (AccessDeniedException | UnsupportedOperationException ignored) {
			// Windows commonly refuses directory handles; file data and the atomic move are still flushed.
		}
	}

	private long lastModifiedMillis(Path path) {
		try {
			return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
		} catch (IOException exception) {
			return Long.MIN_VALUE;
		}
	}

	public static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private record Envelope(int formatVersion, String sha256, String payloadJson) {
	}

	public record StoredValue<T>(T value, String sha256) {
	}
}
