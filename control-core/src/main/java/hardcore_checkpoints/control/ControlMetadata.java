package hardcore_checkpoints.control;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ControlMetadata(
		int schemaVersion,
		int settingsVersion,
		boolean featureEnabled,
		WorldIdentity identity,
		String updatedAtUtc
) {
	public static final int CURRENT_SCHEMA_VERSION = 1;
	public static final int CURRENT_SETTINGS_VERSION = 1;

	public ControlMetadata {
		if (schemaVersion != CURRENT_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Unsupported control schema version: " + schemaVersion);
		}
		if (settingsVersion < 1) {
			throw new IllegalArgumentException("settingsVersion must be positive");
		}
		Objects.requireNonNull(identity, "identity");
		Objects.requireNonNull(updatedAtUtc, "updatedAtUtc");
		if (featureEnabled && identity.activationId() == null) {
			throw new IllegalArgumentException("Enabled control metadata requires an activationId");
		}
	}

	public static ControlMetadata createDisabled(WorldIdentity identity) {
		return new ControlMetadata(
				CURRENT_SCHEMA_VERSION,
				CURRENT_SETTINGS_VERSION,
				false,
				identity,
				Instant.now().toString()
		);
	}

	public ControlMetadata beginActivation(UUID activationId) {
		return new ControlMetadata(
				schemaVersion,
				settingsVersion,
				true,
				identity.withActivationId(activationId),
				Instant.now().toString()
		);
	}

	public ControlMetadata withFeatureEnabled(boolean enabled) {
		return new ControlMetadata(schemaVersion, settingsVersion, enabled, identity, Instant.now().toString());
	}

	public ControlMetadata withWorldInstanceId(UUID worldInstanceId) {
		return new ControlMetadata(
				schemaVersion,
				settingsVersion,
				featureEnabled,
				identity.withWorldInstanceId(worldInstanceId),
				Instant.now().toString()
		);
	}
}
