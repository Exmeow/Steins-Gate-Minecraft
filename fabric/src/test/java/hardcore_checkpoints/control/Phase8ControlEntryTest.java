package hardcore_checkpoints.control;

import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.control.repository.ControlStateRepository;
import hardcore_checkpoints.control.repository.WorldControlBootstrap;
import hardcore_checkpoints.server.admin.AdministratorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Phase8ControlEntryTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void newActivationStagesCycleStateAndKeepsServerAdministrators() throws Exception {
		Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
		AtomicFileStore fileStore = new AtomicFileStore();
		ControlStateRepository repository = new ControlStateRepository(fileStore);
		var initialized = repository.initializeDisabled(world);
		ControlMetadata first = repository.startNewActivation(initialized.layout());
		UUID administrator = UUID.randomUUID();
		new AdministratorRepository(fileStore).add(initialized.layout(), administrator);

		Files.writeString(initialized.layout().instanceRoot().resolve("server-state.json"), "old-state");
		Files.writeString(initialized.layout().instanceRoot().resolve("roster.json"), "old-roster");
		Files.writeString(initialized.layout().checkpointsRoot().resolve("old-checkpoint"), "checkpoint");
		Files.writeString(initialized.layout().transactionsRoot().resolve("old-transaction"), "transaction");
		repository.disable(initialized.layout());
		repository.startNewActivation(initialized.layout());

		Path obsoleteCycle;
		try (var paths = Files.list(initialized.layout().obsoleteRoot())) {
			obsoleteCycle = paths.filter(path -> path.getFileName().toString()
					.startsWith(first.identity().activationId().toString())).findFirst().orElseThrow();
		}
		assertTrue(Files.isRegularFile(obsoleteCycle.resolve("server-state.json")));
		assertTrue(Files.isRegularFile(obsoleteCycle.resolve("roster.json")));
		assertTrue(Files.isRegularFile(obsoleteCycle.resolve("checkpoints/old-checkpoint")));
		assertTrue(Files.isRegularFile(obsoleteCycle.resolve("transactions/old-transaction")));
		assertFalse(Files.exists(initialized.layout().instanceRoot().resolve("server-state.json")));
		assertFalse(Files.exists(initialized.layout().instanceRoot().resolve("roster.json")));
		assertTrue(Files.isDirectory(initialized.layout().checkpointsRoot()));
		assertTrue(Files.isDirectory(initialized.layout().transactionsRoot()));
		assertTrue(new AdministratorRepository(fileStore).contains(initialized.layout(), administrator));
	}

	@Test
	void missingSidecarCanBeExplicitlyAbandonedFromStoppedWorld() throws Exception {
		Path world = Files.createDirectory(temporaryDirectory.resolve("copied-world"));
		ControlStateRepository repository = new ControlStateRepository();
		var initialized = repository.initializeDisabled(world);
		UUID abandonedInstanceId = initialized.metadata().identity().worldInstanceId();
		repository.startNewActivation(initialized.layout());
		deleteTree(initialized.layout().instanceRoot());

		WorldControlBootstrap bootstrap = new WorldControlBootstrap(repository);
		assertTrue(bootstrap.inspect(world).status() == WorldControlBootstrap.BootstrapStatus.INCOMPLETE_CONTROL_DATA);
		repository.abandonMissingControlRoot(world);
		assertTrue(bootstrap.inspect(world).status() == WorldControlBootstrap.BootstrapStatus.FEATURE_DISABLED);
		var reinitialized = repository.initializeDisabled(world);
		assertFalse(reinitialized.metadata().identity().worldInstanceId().equals(abandonedInstanceId));
		repository.startNewActivation(reinitialized.layout());
		assertTrue(bootstrap.inspect(world).status() == WorldControlBootstrap.BootstrapStatus.CONTROL_READY);
	}

	private static void deleteTree(Path root) throws Exception {
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}
}
