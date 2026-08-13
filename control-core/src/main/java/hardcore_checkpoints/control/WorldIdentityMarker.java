package hardcore_checkpoints.control;

import java.util.Objects;

public record WorldIdentityMarker(
		int schemaVersion,
		boolean featureEnabled,
		WorldIdentity identity,
		String controlMetadataSha256
) {
	public WorldIdentityMarker {
		if (schemaVersion != ControlMetadata.CURRENT_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Unsupported marker schema version: " + schemaVersion);
		}
		Objects.requireNonNull(identity, "identity");
		Objects.requireNonNull(controlMetadataSha256, "controlMetadataSha256");
	}

	public static WorldIdentityMarker from(ControlMetadata metadata, String controlMetadataSha256) {
		return new WorldIdentityMarker(
				metadata.schemaVersion(),
				metadata.featureEnabled(),
				metadata.identity(),
				controlMetadataSha256
		);
	}
}
