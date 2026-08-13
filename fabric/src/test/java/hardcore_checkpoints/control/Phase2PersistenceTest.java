package hardcore_checkpoints.control;

import com.google.gson.JsonObject;
import hardcore_checkpoints.control.journal.TransactionJournal;
import hardcore_checkpoints.control.repository.ControlStateRepository;
import hardcore_checkpoints.control.repository.WorldControlBootstrap;
import hardcore_checkpoints.control.transfer.WorldExportImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase2PersistenceTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void detectsCopiesAndAllowsStoppedMoves() throws Exception {
		Path sourceWorld = Files.createDirectory(temporaryDirectory.resolve("source-world"));
		ControlStateRepository repository = new ControlStateRepository();
		ControlStateRepository.InitializedControl initialized = repository.initializeDisabled(sourceWorld);
		WorldControlBootstrap bootstrap = new WorldControlBootstrap(repository);

		assertFalse(initialized.layout().instanceRoot().startsWith(sourceWorld));
		assertEquals(
				WorldControlBootstrap.BootstrapStatus.FEATURE_DISABLED,
				bootstrap.inspect(sourceWorld).status()
		);

		Path copiedWorld = Files.createDirectory(temporaryDirectory.resolve("copied-world"));
		Files.copy(ControlRootLayout.markerPath(sourceWorld), ControlRootLayout.markerPath(copiedWorld));
		assertEquals(
				WorldControlBootstrap.BootstrapStatus.INCOMPLETE_CONTROL_DATA,
				bootstrap.inspect(copiedWorld).status()
		);

		Path movedWorld = temporaryDirectory.resolve("moved-world");
		Files.move(sourceWorld, movedWorld);
		assertEquals(
				WorldControlBootstrap.BootstrapStatus.FEATURE_DISABLED,
				bootstrap.inspect(movedWorld).status()
		);
	}

	@Test
	void isolatesThePreviousActivationCycle() throws Exception {
		Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
		ControlStateRepository repository = new ControlStateRepository();
		ControlStateRepository.InitializedControl initialized = repository.initializeDisabled(world);

		ControlMetadata firstActivation = repository.startNewActivation(initialized.layout());
		repository.disable(initialized.layout());
		ControlMetadata secondActivation = repository.startNewActivation(initialized.layout());

		assertNotEquals(firstActivation.identity().activationId(), secondActivation.identity().activationId());
		assertTrue(Files.isDirectory(initialized.layout().cycle(secondActivation.identity().activationId())));
		try (var obsolete = Files.list(initialized.layout().obsoleteRoot())) {
			assertTrue(obsolete.anyMatch(path -> path.getFileName().toString()
					.startsWith(firstActivation.identity().activationId().toString())));
		}
	}

	@Test
	void importsAsANewInstanceAndRebindsTransactions() throws Exception {
		Path sourceWorld = Files.createDirectory(temporaryDirectory.resolve("export-source"));
		Files.writeString(sourceWorld.resolve("level.dat"), "test-world");
		ControlStateRepository repository = new ControlStateRepository();
		ControlStateRepository.InitializedControl initialized = repository.initializeDisabled(sourceWorld);
		ControlMetadata sourceMetadata = repository.startNewActivation(initialized.layout());
		JsonObject payload = new JsonObject();
		payload.addProperty("stageData", "preserved");
		repository.writeTransaction(
				initialized.layout(),
				TransactionJournal.create(
						sourceMetadata.identity().worldLineageId(),
						sourceMetadata.identity().worldInstanceId(),
						sourceMetadata.identity().activationId(),
						"test",
						"COMMITTED",
						payload
				)
		);

		WorldExportImportService transfer = new WorldExportImportService(path -> false, repository);
		Path archive = temporaryDirectory.resolve("world-export.zip");
		transfer.exportStopped(sourceWorld, archive);
		Path importedWorld = temporaryDirectory.resolve("imported-world");
		WorldExportImportService.ImportedControl imported = transfer.importStopped(archive, importedWorld);

		assertNotEquals(
				sourceMetadata.identity().worldInstanceId(),
				imported.metadata().identity().worldInstanceId()
		);
		assertEquals(sourceMetadata.identity().worldLineageId(), imported.metadata().identity().worldLineageId());
		assertEquals(sourceMetadata.identity().activationId(), imported.metadata().identity().activationId());
		assertEquals(
				WorldControlBootstrap.BootstrapStatus.CONTROL_READY,
				new WorldControlBootstrap(repository).inspect(importedWorld).status()
		);
		assertTrue(repository.readTransactions(imported.layout()).stream()
				.allMatch(transaction -> transaction.worldInstanceId()
						.equals(imported.metadata().identity().worldInstanceId())));
	}
}
