package hardcore_checkpoints.checkpoint;

import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.control.persistence.PathSecurity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.util.Optional;

public final class CheckpointCompensationRepository {
	private static final String FILE_NAME = "checkpoint-compensation.json";

	private final AtomicFileStore fileStore;

	public CheckpointCompensationRepository(AtomicFileStore fileStore) {
		this.fileStore = fileStore;
	}

	public void save(ControlRootLayout layout, CheckpointCompensation compensation) throws IOException {
		PathSecurity.createDirectoriesSecure(layout.instanceRoot(), layout.transactionsRoot());
		fileStore.write(path(layout), compensation);
	}

	public Optional<CheckpointCompensation> load(ControlRootLayout layout) throws IOException {
		return fileStore.readOptional(path(layout), CheckpointCompensation.class)
				.map(AtomicFileStore.StoredValue::value);
	}

	public void clear(ControlRootLayout layout) throws IOException {
		Path path = path(layout);
		PathSecurity.requireSafeFileOrMissing(path);
		Files.deleteIfExists(path);
		Path parent = path.getParent();
		if (parent != null && Files.isDirectory(parent)) {
			PathSecurity.requireSafeDirectory(parent);
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent, path.getFileName() + ".tmp.*")) {
				for (Path temporary : stream) {
					PathSecurity.requireSafeFileOrMissing(temporary);
					Files.deleteIfExists(temporary);
				}
			}
		}
	}

	private static Path path(ControlRootLayout layout) {
		return layout.transactionsRoot().resolve(FILE_NAME);
	}
}
