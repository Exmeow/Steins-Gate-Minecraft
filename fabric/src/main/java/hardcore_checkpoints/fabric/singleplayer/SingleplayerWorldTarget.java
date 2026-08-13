package hardcore_checkpoints.fabric.singleplayer;

import java.nio.file.Path;
import java.util.Objects;

public record SingleplayerWorldTarget(Path worldRoot, String displayName) {
	public SingleplayerWorldTarget {
		Objects.requireNonNull(worldRoot, "worldRoot");
		Objects.requireNonNull(displayName, "displayName");
	}

	public static SingleplayerWorldTarget from(Path savesRoot, String levelId, String displayName) {
		Path normalizedSavesRoot = Objects.requireNonNull(savesRoot, "savesRoot").toAbsolutePath().normalize();
		Objects.requireNonNull(levelId, "levelId");
		Path relativeId = Path.of(levelId);
		if (relativeId.isAbsolute() || relativeId.getNameCount() != 1 || levelId.isBlank()) {
			throw new IllegalArgumentException("Level ID must identify one direct child of the saves directory");
		}
		Path worldRoot = normalizedSavesRoot.resolve(relativeId).normalize();
		if (!normalizedSavesRoot.equals(worldRoot.getParent())) {
			throw new IllegalArgumentException("Level ID escapes the saves directory");
		}
		return new SingleplayerWorldTarget(worldRoot, displayName);
	}
}
