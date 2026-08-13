package hardcore_checkpoints.control.persistence;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class AtomicPathMoves {
	private static final int MAX_ATTEMPTS = 12;
	private static final long INITIAL_RETRY_DELAY_MILLIS = 10L;
	private static final long MAX_RETRY_DELAY_MILLIS = 250L;

	private AtomicPathMoves() {
	}

	public static void move(Path source, Path target, boolean replaceExisting, String unsupportedMessage) throws IOException {
		long retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				if (replaceExisting) {
					Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				} else {
					Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
				}
				return;
			} catch (AtomicMoveNotSupportedException exception) {
				throw new IOException(unsupportedMessage, exception);
			} catch (AccessDeniedException exception) {
				if (attempt == MAX_ATTEMPTS) {
					throw exception;
				}
				sleepBeforeRetry(retryDelayMillis, exception);
				retryDelayMillis = Math.min(retryDelayMillis * 2L, MAX_RETRY_DELAY_MILLIS);
			}
		}
	}

	private static void sleepBeforeRetry(long delayMillis, AccessDeniedException moveFailure) throws IOException {
		try {
			Thread.sleep(delayMillis);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			IOException interrupted = new IOException("Interrupted while retrying an atomic filesystem move", exception);
			interrupted.addSuppressed(moveFailure);
			throw interrupted;
		}
	}
}
