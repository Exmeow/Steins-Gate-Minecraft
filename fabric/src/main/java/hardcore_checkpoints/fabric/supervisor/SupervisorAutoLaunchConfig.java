package hardcore_checkpoints.fabric.supervisor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

record SupervisorAutoLaunchConfig(
		boolean enabled,
		String statusBind,
		int statusPort,
		long healthTimeoutSeconds
) {
	private static final String FILE_NAME = "hardcore_checkpoints-supervisor.properties";

	SupervisorAutoLaunchConfig {
		if (statusBind == null || statusBind.isBlank()) {
			throw new IllegalArgumentException("Supervisor status bind address must not be blank");
		}
		if (statusPort < 1 || statusPort > 65535) {
			throw new IllegalArgumentException("Supervisor status port must be between 1 and 65535");
		}
		if (healthTimeoutSeconds < 1) {
			throw new IllegalArgumentException("Supervisor health timeout must be positive");
		}
	}

	static SupervisorAutoLaunchConfig load(Path configDirectory) throws IOException {
		Files.createDirectories(configDirectory);
		Path path = configDirectory.resolve(FILE_NAME);
		Properties properties = defaults();
		if (Files.isRegularFile(path)) {
			try (InputStream input = Files.newInputStream(path)) {
				properties.load(input);
			}
		} else {
			try (OutputStream output = Files.newOutputStream(
					path,
					StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE
			)) {
				properties.store(output, "Hardcore Checkpoints dedicated supervisor auto-launch");
			}
		}
		String enabledValue = properties.getProperty("auto-launch");
		if (!"true".equalsIgnoreCase(enabledValue) && !"false".equalsIgnoreCase(enabledValue)) {
			throw new IllegalArgumentException("auto-launch must be true or false");
		}
		return new SupervisorAutoLaunchConfig(
				Boolean.parseBoolean(enabledValue),
				properties.getProperty("status-bind"),
				Integer.parseInt(properties.getProperty("status-port")),
				Long.parseLong(properties.getProperty("health-timeout-seconds"))
		);
	}

	private static Properties defaults() {
		Properties properties = new Properties();
		properties.setProperty("auto-launch", "true");
		properties.setProperty("status-bind", "0.0.0.0");
		properties.setProperty("status-port", "25566");
		properties.setProperty("health-timeout-seconds", "300");
		return properties;
	}
}
