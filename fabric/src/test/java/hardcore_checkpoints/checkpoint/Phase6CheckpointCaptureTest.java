package hardcore_checkpoints.checkpoint;

import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.WorldIdentity;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Phase6CheckpointCaptureTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void nearestRecorderSelectionUsesPerPlayerUnion() {
		RecorderSelectionService service = new RecorderSelectionService();
		UUID firstPlayer = UUID.randomUUID();
		UUID secondPlayer = UUID.randomUUID();
		Map<UUID, RecorderSelectionService.PlayerPosition> players = Map.of(
				firstPlayer, new RecorderSelectionService.PlayerPosition("overworld", 0, 64, 0),
				secondPlayer, new RecorderSelectionService.PlayerPosition("overworld", 10, 64, 0)
		);
		var firstCore = new RecorderSelectionService.RecorderCandidate<>("first", "overworld", 1, 64, 0);
		var sharedCore = new RecorderSelectionService.RecorderCandidate<>("shared", "overworld", 5, 64, 0);
		var secondCore = new RecorderSelectionService.RecorderCandidate<>("second", "overworld", 9, 64, 0);

		assertEquals(Set.of("first", "second"), service.selectNearestUnion(
				players,
				List.of(firstCore, sharedCore, secondCore),
				8
		));
		assertTrue(service.selectNearestUnion(players, List.of(firstCore), 8).isEmpty());
	}

	@Test
	void capturePublishesVerifiedPointerAndRetainsOnlyLatestAndPrevious() throws Exception {
		Fixture fixture = fixture();
		Files.writeString(fixture.worldRoot().resolve("level.dat"), "level-one");
		Files.createDirectories(fixture.worldRoot().resolve("region"));
		Files.writeString(fixture.worldRoot().resolve("region/r.0.0.mca"), "region-one");
		Files.writeString(fixture.worldRoot().resolve("session.lock"), "not-snapshotted");

		CheckpointCaptureService service = new CheckpointCaptureService(fixture.fileStore());
		service.preflight(fixture.layout());
		UUID first = UUID.randomUUID();
		service.capture(fixture.layout(), fixture.identity(), first);
		assertFalse(Files.exists(fixture.layout().checkpointsRoot().resolve(first.toString()).resolve("world/session.lock")));

		Files.writeString(fixture.worldRoot().resolve("level.dat"), "level-two");
		UUID second = UUID.randomUUID();
		service.capture(fixture.layout(), fixture.identity(), second);
		Files.writeString(fixture.worldRoot().resolve("level.dat"), "level-three");
		UUID third = UUID.randomUUID();
		service.capture(fixture.layout(), fixture.identity(), third);

		CheckpointPointer pointer = fixture.fileStore().readRequired(
				fixture.layout().instanceRoot().resolve("checkpoint-pointer.json"),
				CheckpointPointer.class
		).value();
		assertEquals(third, pointer.latestCheckpointId());
		assertEquals(second, pointer.previousCheckpointId());
		assertTrue(Files.isDirectory(fixture.layout().checkpointsRoot().resolve(third.toString())));
		assertTrue(Files.isDirectory(fixture.layout().checkpointsRoot().resolve(second.toString())));
		assertFalse(Files.exists(fixture.layout().checkpointsRoot().resolve(first.toString())));
		new SnapshotVerifier(fixture.fileStore()).verifyPublished(fixture.layout(), fixture.identity());
	}

	@Test
	void ignoresMinecraftPlayerDataTemporaryFiles() throws Exception {
		Fixture fixture = fixture();
		Files.writeString(fixture.worldRoot().resolve("level.dat"), "level");
		Path playerData = Files.createDirectory(fixture.worldRoot().resolve("playerdata"));
		String playerId = "74000442-2671-413f-9399-6e4ef29e9590";
		Files.writeString(playerData.resolve(playerId + ".dat"), "published");
		Files.writeString(playerData.resolve(playerId + "-2757004688273712691.dat"), "temporary");

		CheckpointCaptureService service = new CheckpointCaptureService(fixture.fileStore());
		service.preflight(fixture.layout());
		UUID checkpointId = UUID.randomUUID();
		var captured = service.capture(
				fixture.layout(),
				fixture.identity(),
				checkpointId
		);

		assertTrue(captured.manifest().files().stream()
				.anyMatch(file -> file.relativePath().equals("playerdata/" + playerId + ".dat")));
		assertFalse(captured.manifest().files().stream()
				.anyMatch(file -> file.relativePath().contains("2757004688273712691")));
		assertFalse(Files.exists(fixture.layout().checkpointsRoot()
				.resolve(checkpointId.toString())
				.resolve("world/playerdata/" + playerId + "-2757004688273712691.dat")));
	}
	@Test
	void verifierRejectsModifiedSnapshotFile() throws Exception {
		Fixture fixture = fixture();
		Files.writeString(fixture.worldRoot().resolve("level.dat"), "original");
		CheckpointCaptureService service = new CheckpointCaptureService(fixture.fileStore());
		service.preflight(fixture.layout());
		UUID checkpointId = UUID.randomUUID();
		service.capture(fixture.layout(), fixture.identity(), checkpointId);

		Files.writeString(
				fixture.layout().checkpointsRoot().resolve(checkpointId.toString()).resolve("world/level.dat"),
				"tampered"
		);
		assertThrows(IOException.class, () ->
				new SnapshotVerifier(fixture.fileStore()).verifyPublished(fixture.layout(), fixture.identity()));
	}

	@Test
	void preflightRejectsSymbolicLinksInWorldTree() throws Exception {
		Fixture fixture = fixture();
		Path external = temporaryDirectory.resolve("outside.txt");
		Files.writeString(external, "outside");
		Path link = fixture.worldRoot().resolve("escape-link");
		try {
			Files.createSymbolicLink(link, external);
		} catch (UnsupportedOperationException | IOException exception) {
			return;
		}
		assertThrows(IOException.class, () -> new CheckpointCaptureService(fixture.fileStore()).preflight(fixture.layout()));
	}

	@Test
	void clearingCompensationAlsoRemovesRecoverableTemporaryFiles() throws Exception {
		Fixture fixture = fixture();
		CheckpointCompensationRepository repository = new CheckpointCompensationRepository(fixture.fileStore());
		CheckpointCompensation compensation = CheckpointCompensation.create(
				fixture.identity(),
				UUID.randomUUID(),
				List.of()
		);
		repository.save(fixture.layout(), compensation);
		Path authoritative = fixture.layout().transactionsRoot().resolve("checkpoint-compensation.json");
		Path recoverableTemporary = authoritative.resolveSibling(
				authoritative.getFileName() + ".tmp." + UUID.randomUUID()
		);
		Files.copy(authoritative, recoverableTemporary);

		repository.clear(fixture.layout());
		assertFalse(Files.exists(authoritative));
		assertFalse(Files.exists(recoverableTemporary));
		assertTrue(repository.load(fixture.layout()).isEmpty());
	}

	private Fixture fixture() throws IOException {
		Path worldRoot = temporaryDirectory.resolve("world-" + UUID.randomUUID());
		Files.createDirectory(worldRoot);
		WorldIdentity identity = new WorldIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		ControlRootLayout layout = ControlRootLayout.forWorld(worldRoot, identity.worldInstanceId());
		Files.createDirectories(layout.instanceRoot());
		return new Fixture(worldRoot, layout, identity, new AtomicFileStore());
	}

	private record Fixture(
			Path worldRoot,
			ControlRootLayout layout,
			WorldIdentity identity,
			AtomicFileStore fileStore
	) {
	}
}
