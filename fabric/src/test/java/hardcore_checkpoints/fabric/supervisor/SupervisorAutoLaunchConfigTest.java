package hardcore_checkpoints.fabric.supervisor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SupervisorAutoLaunchConfigTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void writesAndLoadsDedicatedServerDefaults() throws Exception {
		SupervisorAutoLaunchConfig config = SupervisorAutoLaunchConfig.load(temporaryDirectory);

		assertTrue(config.enabled());
		assertEquals("0.0.0.0", config.statusBind());
		assertEquals(25566, config.statusPort());
		assertEquals(300, config.healthTimeoutSeconds());
		assertTrue(Files.isRegularFile(temporaryDirectory.resolve("hardcore_checkpoints-supervisor.properties")));
	}

	@Test
	void loadsOwnerOverrides() throws Exception {
		Files.writeString(
				temporaryDirectory.resolve("hardcore_checkpoints-supervisor.properties"),
				"auto-launch=false\nstatus-bind=127.0.0.1\nstatus-port=25570\nhealth-timeout-seconds=45\n"
		);

		SupervisorAutoLaunchConfig config = SupervisorAutoLaunchConfig.load(temporaryDirectory);

		assertFalse(config.enabled());
		assertEquals("127.0.0.1", config.statusBind());
		assertEquals(25570, config.statusPort());
		assertEquals(45, config.healthTimeoutSeconds());
	}

	@Test
	void rejectsInvalidBooleanValues() throws Exception {
		Files.writeString(
				temporaryDirectory.resolve("hardcore_checkpoints-supervisor.properties"),
				"auto-launch=tru\nstatus-bind=127.0.0.1\nstatus-port=25566\nhealth-timeout-seconds=300\n"
		);

		assertThrows(IllegalArgumentException.class, () -> SupervisorAutoLaunchConfig.load(temporaryDirectory));
	}
}
