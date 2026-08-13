package hardcore_checkpoints.fabric.singleplayer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SingleplayerWorldTargetTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void equalDisplayNamesStillResolveDistinctWorldDirectories() {
		SingleplayerWorldTarget first = SingleplayerWorldTarget.from(temporaryDirectory, "World", "Same Name");
		SingleplayerWorldTarget second = SingleplayerWorldTarget.from(temporaryDirectory, "World (1)", "Same Name");

		assertEquals(first.displayName(), second.displayName());
		assertNotEquals(first.worldRoot(), second.worldRoot());
		assertEquals(temporaryDirectory.resolve("World").toAbsolutePath().normalize(), first.worldRoot());
		assertEquals(temporaryDirectory.resolve("World (1)").toAbsolutePath().normalize(), second.worldRoot());
	}

	@Test
	void levelIdCannotEscapeSavesDirectory() {
		assertThrows(IllegalArgumentException.class,
				() -> SingleplayerWorldTarget.from(temporaryDirectory, "..", "Outside"));
		assertThrows(IllegalArgumentException.class,
				() -> SingleplayerWorldTarget.from(temporaryDirectory, "nested/world", "Nested"));
	}
}
