package hardcore_checkpoints.supervisor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RunConfigurationTest {
	@Test
	void parsesSupervisorOptionsWithoutRewritingChildArguments() throws Exception {
		RunConfiguration configuration = RunConfiguration.parse(new String[]{
				"--world", "server/world",
				"--status-bind", "127.0.0.1",
				"--status-port", "25570",
				"--control-port", "0",
				"--health-timeout-seconds", "45",
				"--retry-recovery",
				"--handoff-pid", "1234",
				"--",
				"java", "-Xmx4G", "-jar", "server.jar", "nogui"
		});

		assertEquals(25570, configuration.statusPort());
		assertEquals(45, configuration.healthTimeout().toSeconds());
		assertTrue(configuration.retryRecovery());
		assertEquals(1234L, configuration.handoffPid());
		assertEquals(
				java.util.List.of("java", "-Xmx4G", "-jar", "server.jar", "nogui"),
				configuration.childCommand()
		);
	}

	@Test
	void requiresWorldAndChildCommand() {
		assertThrows(IllegalArgumentException.class, () -> RunConfiguration.parse(new String[]{"--world", "world"}));
		assertThrows(IllegalArgumentException.class, () -> RunConfiguration.parse(new String[]{"--", "java"}));
	}
}
