package hardcore_checkpoints.control.repository;

import hardcore_checkpoints.control.ControlMetadata;
import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.WorldBinding;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.control.persistence.PathSecurity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

public final class WorldBindingRepository {
	private final AtomicFileStore fileStore;

	public WorldBindingRepository(AtomicFileStore fileStore) {
		this.fileStore = fileStore;
	}

	public WorldBinding create(ControlRootLayout layout, ControlMetadata metadata) throws IOException {
		WorldBinding binding = WorldBinding.create(
				metadata.identity().worldInstanceId(),
				normalizedPath(layout.worldRoot())
		);
		fileStore.write(layout.worldBinding(), binding);
		return binding;
	}

	public BindingResult validateOrRebind(ControlRootLayout layout, ControlMetadata metadata) throws IOException {
		WorldBinding binding = fileStore.readRequired(layout.worldBinding(), WorldBinding.class).value();
		if (!binding.worldInstanceId().equals(metadata.identity().worldInstanceId())) {
			return new BindingResult(BindingStatus.CONFLICT, binding, "Binding worldInstanceId does not match control metadata");
		}

		Path currentWorldPath = PathSecurity.requireSafeDirectory(layout.worldRoot());
		Path boundWorldPath;
		try {
			boundWorldPath = PathSecurity.normalizeAbsolute(Path.of(binding.normalizedWorldPath()));
		} catch (InvalidPathException exception) {
			return new BindingResult(BindingStatus.CONFLICT, binding, "Binding contains an invalid world path");
		}

		if (Files.exists(boundWorldPath, LinkOption.NOFOLLOW_LINKS)) {
			PathSecurity.requireSafeDirectory(boundWorldPath);
			if (Files.isSameFile(boundWorldPath, currentWorldPath)) {
				return new BindingResult(BindingStatus.MATCHED, binding, null);
			}
			return new BindingResult(
					BindingStatus.CONFLICT,
					binding,
					"The previously bound world path still exists: " + boundWorldPath
			);
		}

		WorldBinding rebound = WorldBinding.create(
				metadata.identity().worldInstanceId(),
				normalizedPath(currentWorldPath)
		);
		fileStore.write(layout.worldBinding(), rebound);
		return new BindingResult(BindingStatus.REBOUND_AFTER_MOVE, rebound, null);
	}

	private static String normalizedPath(Path path) throws IOException {
		return PathSecurity.requireSafeDirectory(path).toRealPath(LinkOption.NOFOLLOW_LINKS).toString();
	}

	public enum BindingStatus {
		MATCHED,
		REBOUND_AFTER_MOVE,
		CONFLICT
	}

	public record BindingResult(BindingStatus status, WorldBinding binding, String issue) {
		public BindingResult {
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(binding, "binding");
		}
	}
}
