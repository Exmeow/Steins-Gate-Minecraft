package hardcore_checkpoints.control.transfer;

import hardcore_checkpoints.control.ControlMetadata;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ExportManifest(
		int schemaVersion,
		String createdAtUtc,
		String sourceWorldDirectoryName,
		ControlMetadata sourceControlMetadata,
		List<ArchiveFile> files
) {
	public ExportManifest {
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("Unsupported export manifest schema: " + schemaVersion);
		}
		Objects.requireNonNull(createdAtUtc, "createdAtUtc");
		Objects.requireNonNull(sourceWorldDirectoryName, "sourceWorldDirectoryName");
		Objects.requireNonNull(sourceControlMetadata, "sourceControlMetadata");
		files = List.copyOf(files);
	}

	public static ExportManifest create(
			String sourceWorldDirectoryName,
			ControlMetadata sourceControlMetadata,
			List<ArchiveFile> files
	) {
		return new ExportManifest(
				1,
				Instant.now().toString(),
				sourceWorldDirectoryName,
				sourceControlMetadata,
				files
		);
	}

	public record ArchiveFile(String archivePath, long size, String sha256) {
		public ArchiveFile {
			Objects.requireNonNull(archivePath, "archivePath");
			Objects.requireNonNull(sha256, "sha256");
			if (size < 0) {
				throw new IllegalArgumentException("Archive file size must not be negative");
			}
		}
	}
}
