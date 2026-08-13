package hardcore_checkpoints.supervisor;

import hardcore_checkpoints.control.transfer.FileLockWorldActivityProbe;
import hardcore_checkpoints.control.transfer.WorldExportImportService;
import hardcore_checkpoints.recovery.OfflineRollbackService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;

public final class SupervisorMain {
	private SupervisorMain() {
	}

	public static void main(String[] args) {
		int exitCode;
		try {
			exitCode = execute(args);
		} catch (Exception exception) {
			System.err.println("Hardcore Checkpoints supervisor failed: " + exception.getMessage());
			exception.printStackTrace(System.err);
			exitCode = 1;
		}
		if (exitCode != 0) {
			System.exit(exitCode);
		}
	}

	static int execute(String[] args) throws Exception {
		if (args.length == 0) {
			printUsage();
			return 2;
		}
		String command = args[0];
		String[] remainder = Arrays.copyOfRange(args, 1, args.length);
		return switch (command) {
			case "run" -> run(remainder);
			case "export" -> exportWorld(remainder);
			case "import" -> importWorld(remainder);
			case "retry" -> retryRecovery(remainder);
			default -> {
				printUsage();
				yield 2;
			}
		};
	}

	private static int run(String[] args) throws Exception {
		RunConfiguration configuration = RunConfiguration.parse(args);
		try (SupervisorEngine engine = new SupervisorEngine(configuration)) {
			engine.run();
		}
		return 0;
	}

	private static int exportWorld(String[] args) throws Exception {
		Path world = requiredPath(args, "--world");
		Path archive = requiredPath(args, "--archive");
		new WorldExportImportService(new FileLockWorldActivityProbe()).exportStopped(world, archive);
		System.out.println("Exported checkpoint world to " + archive.toAbsolutePath().normalize());
		return 0;
	}

	private static int importWorld(String[] args) throws Exception {
		Path archive = requiredPath(args, "--archive");
		Path world = requiredPath(args, "--world");
		new WorldExportImportService(new FileLockWorldActivityProbe()).importStopped(archive, world);
		System.out.println("Imported checkpoint world to " + world.toAbsolutePath().normalize());
		return 0;
	}

	private static int retryRecovery(String[] args) throws Exception {
		Path world = requiredPath(args, "--world");
		var located = new SupervisorWorldLocator().locate(world);
		new OfflineRollbackService(new FileLockWorldActivityProbe())
				.retryStopped(located.layout(), Instant.now());
		System.out.println("Retried recovery transaction for " + world.toAbsolutePath().normalize());
		return 0;
	}

	private static Path requiredPath(String[] args, String option) {
		for (int index = 0; index < args.length - 1; index++) {
			if (option.equals(args[index])) {
				return Path.of(args[index + 1]);
			}
		}
		throw new IllegalArgumentException(option + " is required");
	}

	private static void printUsage() {
		System.err.println("Usage:");
		System.err.println("  supervisor run --world <path> [--status-bind <address>] [--status-port <port>]");
		System.err.println("      [--control-port <port>] [--health-timeout-seconds <seconds>] [--retry-recovery]");
		System.err.println("      [--handoff-pid <pid>] -- <java> <minecraft server arguments...>");
		System.err.println("  supervisor export --world <path> --archive <zip>");
		System.err.println("  supervisor import --archive <zip> --world <new-world-path>");
		System.err.println("  supervisor retry --world <path>");
	}
}
