package hardcore_checkpoints.fabric.supervisor;

import hardcore_checkpoints.HardcoreCheckpoints;
import hardcore_checkpoints.control.persistence.AtomicPathMoves;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

final class FabricSupervisorAutoLauncher {
	private static final String EMBEDDED_SUPERVISOR = "/assets/hardcore_checkpoints/runtime/supervisor.jar";
	private static final String RUNTIME_DIRECTORY = ".hardcore_checkpoints/runtime";

	private FabricSupervisorAutoLauncher() {
	}

	static boolean launchAndStop(MinecraftServer server) throws IOException {
		SupervisorAutoLaunchConfig config = SupervisorAutoLaunchConfig.load(
				FabricLoader.getInstance().getConfigDir()
		);
		if (!config.enabled()) {
			return false;
		}
		Path gameDirectory = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
		Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		Path supervisorJar = extractSupervisor(gameDirectory);
		List<String> minecraftCommand = currentJavaCommand();
		List<String> command = buildSupervisorCommand(
				minecraftCommand,
				supervisorJar,
				worldRoot,
				config,
				ProcessHandle.current().pid()
		);

		if (!server.saveEverything(true, true, true)) {
			throw new IOException("Minecraft saveEverything failed before supervisor handoff");
		}
		ProcessBuilder builder = new ProcessBuilder(command)
				.directory(gameDirectory.toFile())
				.redirectInput(ProcessBuilder.Redirect.PIPE)
				.redirectOutput(ProcessBuilder.Redirect.INHERIT)
				.redirectError(ProcessBuilder.Redirect.INHERIT);
		Process supervisor = builder.start();
		try {
			FabricSupervisorConsoleProxy.activate(supervisor);
			startLifecycleRelay(supervisor);
		} catch (RuntimeException exception) {
			FabricSupervisorConsoleProxy.release(supervisor);
			supervisor.destroy();
			throw exception;
		}
		HardcoreCheckpoints.LOGGER.info(
				"Started embedded Hardcore Checkpoints supervisor handoff for world {} from Minecraft pid {} to supervisor pid {}",
				worldRoot,
				ProcessHandle.current().pid(),
				supervisor.pid()
		);
		server.execute(() -> server.halt(false));
		return true;
	}

	static Thread startLifecycleRelay(Process supervisor) {
		Thread relay = Thread.ofPlatform()
				.name("hardcore-checkpoints-supervisor-relay")
				.daemon(false)
				.unstarted(() -> {
					try {
						int exitCode = supervisor.waitFor();
						HardcoreCheckpoints.LOGGER.info(
								"Hardcore Checkpoints supervisor pid {} exited with code {}; releasing launcher lifecycle relay",
								supervisor.pid(),
								exitCode
						);
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						HardcoreCheckpoints.LOGGER.warn("Supervisor lifecycle relay was interrupted", exception);
					} finally {
						FabricSupervisorConsoleProxy.release(supervisor);
					}
				});
		relay.start();
		return relay;
	}
	static List<String> buildSupervisorCommand(
			List<String> minecraftCommand,
			Path supervisorJar,
			Path worldRoot,
			SupervisorAutoLaunchConfig config,
			long handoffPid
	) {
		if (minecraftCommand.isEmpty()) {
			throw new IllegalArgumentException("Minecraft command must not be empty");
		}
		List<String> command = new ArrayList<>();
		command.add(minecraftCommand.getFirst());
		command.add("--add-modules");
		command.add("jdk.httpserver");
		command.add("-jar");
		command.add(supervisorJar.toString());
		command.add("run");
		command.add("--world");
		command.add(worldRoot.toString());
		command.add("--status-bind");
		command.add(config.statusBind());
		command.add("--status-port");
		command.add(Integer.toString(config.statusPort()));
		command.add("--health-timeout-seconds");
		command.add(Long.toString(config.healthTimeoutSeconds()));
		command.add("--handoff-pid");
		command.add(Long.toString(handoffPid));
		command.add("--");
		command.addAll(minecraftCommand);
		return List.copyOf(command);
	}
	private static Path extractSupervisor(Path gameDirectory) throws IOException {
		Path runtimeDirectory = gameDirectory.resolve(RUNTIME_DIRECTORY);
		Files.createDirectories(runtimeDirectory);
		Path target = runtimeDirectory.resolve("supervisor.jar");
		Path temporary = runtimeDirectory.resolve("supervisor.jar.tmp");
		try (InputStream input = FabricSupervisorAutoLauncher.class.getResourceAsStream(EMBEDDED_SUPERVISOR)) {
			if (input == null) {
				throw new IOException("Embedded supervisor resource is missing from the Mod JAR");
			}
			Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
		}
		try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
			channel.force(true);
		}
		try {
			AtomicPathMoves.move(
					temporary,
					target,
					true,
					"Filesystem does not support atomic embedded supervisor publication"
			);
		} finally {
			Files.deleteIfExists(temporary);
		}
		return target;
	}

	private static List<String> currentJavaCommand() throws IOException {
		ProcessHandle.Info info = ProcessHandle.current().info();
		String executable = info.command().orElseThrow(() ->
				new IOException("Current Java executable is unavailable; cannot hand off to the supervisor"));
		String[] directArguments = info.arguments().orElse(null);
		if (directArguments != null && directArguments.length > 0) {
			List<String> command = new ArrayList<>(directArguments.length + 1);
			command.add(executable);
			command.addAll(List.of(directArguments));
			return List.copyOf(command);
		}

		String mainClass = currentMainClass();
		List<String> command = new ArrayList<>();
		command.add(executable);
		command.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
		command.add("-cp");
		command.add(System.getProperty("java.class.path"));
		command.add(mainClass);
		command.addAll(List.of(FabricLoader.getInstance().getLaunchArguments(false)));
		return List.copyOf(command);
	}

	private static String currentMainClass() throws IOException {
		String classPath = System.getProperty("java.class.path", "");
		String[] classPathEntries = classPath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator));
		if (classPathEntries.length == 1) {
			Path entry = Path.of(classPathEntries[0]).toAbsolutePath().normalize();
			if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".jar")) {
				try (java.util.jar.JarFile jar = new java.util.jar.JarFile(entry.toFile())) {
					String mainClass = jar.getManifest() == null
							? null
							: jar.getManifest().getMainAttributes().getValue(java.util.jar.Attributes.Name.MAIN_CLASS);
					if (mainClass != null && !mainClass.isBlank()) {
						return mainClass;
					}
				}
			}
		}
		String javaCommand = System.getProperty("sun.java.command", "").trim();
		if (javaCommand.isEmpty()) {
			throw new IOException("Current Java main class is unavailable; cannot hand off to the supervisor");
		}
		String candidate = javaCommand.split("\\s+", 2)[0];
		if (candidate.endsWith(".jar")) {
			try (java.util.jar.JarFile jar = new java.util.jar.JarFile(candidate)) {
				String mainClass = jar.getManifest() == null
						? null
						: jar.getManifest().getMainAttributes().getValue(java.util.jar.Attributes.Name.MAIN_CLASS);
				if (mainClass == null || mainClass.isBlank()) {
					throw new IOException("Current server JAR does not declare Main-Class: " + candidate);
				}
				return mainClass;
			}
		}
		if (!candidate.matches("[A-Za-z_$][A-Za-z0-9_.$]*")) {
			throw new IOException("Current Java main class cannot be reconstructed safely: " + candidate);
		}
		return candidate;
	}
}
