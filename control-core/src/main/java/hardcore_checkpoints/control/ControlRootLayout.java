package hardcore_checkpoints.control;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public record ControlRootLayout(
		Path worldRoot,
		Path worldParent,
		Path ownershipRoot,
		Path instancesRoot,
		Path instanceRoot
) {
	public static final String OWNERSHIP_DIRECTORY = ".hardcore_checkpoints";
	public static final String WORLD_MARKER_FILE = "hardcore_checkpoints.identity.json";

	public ControlRootLayout {
		Objects.requireNonNull(worldRoot, "worldRoot");
		Objects.requireNonNull(worldParent, "worldParent");
		Objects.requireNonNull(ownershipRoot, "ownershipRoot");
		Objects.requireNonNull(instancesRoot, "instancesRoot");
		Objects.requireNonNull(instanceRoot, "instanceRoot");
	}

	public static ControlRootLayout forWorld(Path worldRoot, UUID worldInstanceId) {
		Path normalizedWorldRoot = worldRoot.toAbsolutePath().normalize();
		Path parent = Objects.requireNonNull(normalizedWorldRoot.getParent(), "World root must have a parent directory");
		Path ownership = parent.resolve(OWNERSHIP_DIRECTORY).normalize();
		Path instances = ownership.resolve("instances").normalize();
		Path instance = instances.resolve(worldInstanceId.toString()).normalize();
		if (ownership.startsWith(normalizedWorldRoot) || instance.startsWith(normalizedWorldRoot)) {
			throw new IllegalArgumentException("Control root must not be inside the world directory");
		}
		return new ControlRootLayout(normalizedWorldRoot, parent, ownership, instances, instance);
	}

	public static Path markerPath(Path worldRoot) {
		return worldRoot.toAbsolutePath().normalize().resolve(WORLD_MARKER_FILE);
	}

	public Path controlMetadata() {
		return instanceRoot.resolve("control.json");
	}

	public Path worldBinding() {
		return instanceRoot.resolve("binding.json");
	}

	public Path activationSwitchJournal() {
		return instanceRoot.resolve("activation-switch.json");
	}

	public Path cyclesRoot() {
		return instanceRoot.resolve("cycles");
	}

	public Path cycle(UUID activationId) {
		return cyclesRoot().resolve(activationId.toString());
	}

	public Path obsoleteRoot() {
		return instanceRoot.resolve("obsolete");
	}

	public Path transactionsRoot() {
		return instanceRoot.resolve("transactions");
	}

	public Path checkpointsRoot() {
		return instanceRoot.resolve("checkpoints");
	}
}
