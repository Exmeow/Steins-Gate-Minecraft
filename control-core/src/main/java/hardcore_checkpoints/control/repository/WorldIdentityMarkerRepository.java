package hardcore_checkpoints.control.repository;

import hardcore_checkpoints.control.ControlMetadata;
import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.WorldIdentityMarker;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.control.persistence.PathSecurity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public final class WorldIdentityMarkerRepository {
	private final AtomicFileStore fileStore;

	public WorldIdentityMarkerRepository(AtomicFileStore fileStore) {
		this.fileStore = fileStore;
	}

	public Optional<AtomicFileStore.StoredValue<WorldIdentityMarker>> read(Path worldRoot) throws IOException {
		PathSecurity.requireSafeDirectory(worldRoot);
		return fileStore.readOptional(ControlRootLayout.markerPath(worldRoot), WorldIdentityMarker.class);
	}

	public String write(Path worldRoot, ControlMetadata metadata, String controlMetadataSha256) throws IOException {
		Path safeWorldRoot = PathSecurity.requireSafeDirectory(worldRoot);
		return fileStore.write(
				ControlRootLayout.markerPath(safeWorldRoot),
				WorldIdentityMarker.from(metadata, controlMetadataSha256)
		);
	}
}
