package hardcore_checkpoints.recovery;

import hardcore_checkpoints.checkpoint.CheckpointCaptureService;
import hardcore_checkpoints.checkpoint.CheckpointPointer;
import hardcore_checkpoints.control.ControlMetadata;
import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.control.repository.ControlStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase7OfflineRollbackTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void publishesTheCheckpointOnlyWhileStoppedAndKeepsRescueUntilHealth() throws Exception {
		Fixture fixture = createCheckpointFixture("world");
		Files.writeString(fixture.world.resolve("timeline.txt"), "after-death");
		DeathTransaction death = fixture.deaths.begin(
				fixture.layout,
				fixture.pointer,
				UUID.randomUUID(),
				Instant.parse("2026-08-12T12:00:00Z")
		);
		OfflineRollbackService recovery = new OfflineRollbackService(path -> false, fixture.controls);

		OfflineRollbackService.RestoreResult restored = recovery.restoreStopped(
				fixture.layout,
				Instant.parse("2026-08-12T12:00:20Z")
		);

		assertEquals(RollbackFilesystemStage.WORLD_PUBLISHED, restored.stage());
		assertEquals("checkpoint", Files.readString(fixture.world.resolve("timeline.txt")));
		assertEquals("after-death", Files.readString(restored.rescueRoot().resolve("timeline.txt")));
		assertEquals(DeathTransactionStage.RESTORING, fixture.deaths.requireActive(fixture.layout).stage());

		OfflineRollbackService.RestoreResult repeated = recovery.restoreStopped(
				fixture.layout,
				Instant.parse("2026-08-12T12:00:21Z")
		);
		assertEquals(RollbackFilesystemStage.WORLD_PUBLISHED, repeated.stage());

		recovery.confirmHealthy(
				fixture.layout,
				fixture.metadata.identity(),
				death.checkpointId(),
				Instant.parse("2026-08-12T12:00:22Z")
		);
		assertTrue(fixture.deaths.findActive(fixture.layout).isEmpty());
		assertFalse(Files.exists(restored.rescueRoot()));
	}

	@Test
	void refusesToTouchAWorldReportedAsRunning() throws Exception {
		Fixture fixture = createCheckpointFixture("running-world");
		fixture.deaths.begin(fixture.layout, fixture.pointer, UUID.randomUUID(), Instant.now());
		Files.writeString(fixture.world.resolve("timeline.txt"), "still-running");

		assertThrows(
				IOException.class,
				() -> new OfflineRollbackService(path -> true, fixture.controls)
						.restoreStopped(fixture.layout, Instant.now())
		);
		assertEquals("still-running", Files.readString(fixture.world.resolve("timeline.txt")));
		assertEquals(DeathTransactionStage.COUNTDOWN, fixture.deaths.requireActive(fixture.layout).stage());
	}

	@Test
	void freezesTheTransactionWhenCheckpointVerificationFails() throws Exception {
		Fixture fixture = createCheckpointFixture("broken-checkpoint-world");
		fixture.deaths.begin(fixture.layout, fixture.pointer, UUID.randomUUID(), Instant.now());
		Files.writeString(fixture.world.resolve("timeline.txt"), "must-survive-failure");
		Path snapshotFile = fixture.layout.checkpointsRoot()
				.resolve(fixture.pointer.latestCheckpointId().toString())
				.resolve("world")
				.resolve("timeline.txt");
		Files.writeString(snapshotFile, "corrupt");

		assertThrows(
				IOException.class,
				() -> new OfflineRollbackService(path -> false, fixture.controls)
						.restoreStopped(fixture.layout, Instant.now())
		);
		assertEquals("must-survive-failure", Files.readString(fixture.world.resolve("timeline.txt")));
		DeathTransaction failed = fixture.deaths.requireActive(fixture.layout);
		assertEquals(DeathTransactionStage.RECOVERY_FAILED, failed.stage());
		assertFalse(failed.failureCode().isBlank());
	}

	private Fixture createCheckpointFixture(String worldName) throws Exception {
		Path world = Files.createDirectory(temporaryDirectory.resolve(worldName));
		Files.writeString(world.resolve("level.dat"), "level");
		Files.writeString(world.resolve("timeline.txt"), "checkpoint");
		ControlStateRepository controls = new ControlStateRepository();
		ControlStateRepository.InitializedControl initialized = controls.initializeDisabled(world);
		ControlMetadata metadata = controls.startNewActivation(initialized.layout());
		CheckpointPointer pointer = new CheckpointCaptureService(new AtomicFileStore())
				.capture(initialized.layout(), metadata.identity(), UUID.randomUUID())
				.pointer();
		DeathTransactionRepository deaths = new DeathTransactionRepository(controls);
		return new Fixture(world, initialized.layout(), metadata, pointer, controls, deaths);
	}

	private record Fixture(
			Path world,
			ControlRootLayout layout,
			ControlMetadata metadata,
			CheckpointPointer pointer,
			ControlStateRepository controls,
			DeathTransactionRepository deaths
	) {
	}
}
