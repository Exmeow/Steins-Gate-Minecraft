package hardcore_checkpoints.recovery;

import hardcore_checkpoints.checkpoint.CheckpointPointer;
import hardcore_checkpoints.control.ControlMetadata;
import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.WorldIdentity;
import hardcore_checkpoints.control.repository.ControlStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase7DeathTransactionTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void persistsTheFirstDeathAndKeepsItsOriginalDeadline() throws Exception {
		Fixture fixture = createFixture("world");
		Instant firstDeathAt = Instant.parse("2026-08-12T12:00:00Z");
		UUID firstPlayer = UUID.randomUUID();
		DeathTransaction first = fixture.deaths.begin(
				fixture.layout,
				fixture.pointer,
				firstPlayer,
				firstDeathAt
		);

		DeathTransaction laterDeath = fixture.deaths.begin(
				fixture.layout,
				fixture.pointer,
				UUID.randomUUID(),
				firstDeathAt.plusSeconds(9)
		);
		DeathTransaction reloaded = fixture.deaths.requireActive(fixture.layout);

		assertEquals(first.transactionId(), laterDeath.transactionId());
		assertEquals(firstPlayer, reloaded.firstDeathPlayerId());
		assertEquals(firstDeathAt.plusSeconds(20), reloaded.countdownDeadline());
		assertEquals(DeathTransactionStage.COUNTDOWN, reloaded.stage());
		assertTrue(reloaded.stage().shouldResumeAutomaticallyAfterRestart());
	}

	@Test
	void enforcesRecoveryStagesAndAllowsRetryingTheSameTransaction() throws Exception {
		Fixture fixture = createFixture("recovery-world");
		Instant now = Instant.parse("2026-08-12T12:00:00Z");
		DeathTransaction countdown = fixture.deaths.begin(
				fixture.layout,
				fixture.pointer,
				UUID.randomUUID(),
				now
		);
		DeathTransaction pending = countdown.transitionTo(DeathTransactionStage.ROLLBACK_PENDING, now.plusSeconds(20));
		DeathTransaction restoring = pending.transitionTo(DeathTransactionStage.RESTORING, now.plusSeconds(21));
		DeathTransaction failed = restoring.fail("MANIFEST_MISMATCH", "Checkpoint verification failed", now.plusSeconds(22));
		DeathTransaction retrying = failed.transitionTo(DeathTransactionStage.RESTORING, now.plusSeconds(30));
		DeathTransaction recovered = retrying.transitionTo(DeathTransactionStage.RECOVERED, now.plusSeconds(31));

		assertEquals(countdown.transactionId(), recovered.transactionId());
		assertEquals(countdown.countdownDeadlineUtc(), recovered.countdownDeadlineUtc());
		assertThrows(
				IllegalStateException.class,
				() -> countdown.transitionTo(DeathTransactionStage.RESTORING, now)
		);
		fixture.deaths.save(fixture.layout, recovered);
		assertTrue(fixture.deaths.findActive(fixture.layout).isEmpty());
	}

	@Test
	void rejectsACheckpointFromAnotherActivation() throws Exception {
		Fixture fixture = createFixture("identity-world");
		WorldIdentity wrongIdentity = fixture.metadata.identity().withActivationId(UUID.randomUUID());
		CheckpointPointer wrongPointer = CheckpointPointer.publish(
				wrongIdentity,
				UUID.randomUUID(),
				"a".repeat(64),
				null
		);

		assertThrows(
				IOException.class,
				() -> fixture.deaths.begin(
						fixture.layout,
						wrongPointer,
						UUID.randomUUID(),
						Instant.now()
				)
		);
	}

	private Fixture createFixture(String directoryName) throws Exception {
		Path world = Files.createDirectory(temporaryDirectory.resolve(directoryName));
		ControlStateRepository controls = new ControlStateRepository();
		ControlStateRepository.InitializedControl initialized = controls.initializeDisabled(world);
		ControlMetadata metadata = controls.startNewActivation(initialized.layout());
		CheckpointPointer pointer = CheckpointPointer.publish(
				metadata.identity(),
				UUID.randomUUID(),
				"0".repeat(64),
				null
		);
		return new Fixture(
				initialized.layout(),
				metadata,
				pointer,
				new DeathTransactionRepository(controls)
		);
	}

	private record Fixture(
			ControlRootLayout layout,
			ControlMetadata metadata,
			CheckpointPointer pointer,
			DeathTransactionRepository deaths
	) {
	}
}
