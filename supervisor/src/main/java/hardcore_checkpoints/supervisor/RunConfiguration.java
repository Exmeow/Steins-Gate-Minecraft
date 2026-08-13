package hardcore_checkpoints.supervisor;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

record RunConfiguration(
		Path worldRoot,
		InetAddress statusBindAddress,
		int statusPort,
		int controlPort,
		Duration healthTimeout,
		boolean retryRecovery,
		long handoffPid,
		List<String> childCommand
) {
	RunConfiguration {
		worldRoot = worldRoot.toAbsolutePath().normalize();
		childCommand = List.copyOf(childCommand);
		if (statusPort < 0 || statusPort > 65535 || controlPort < 0 || controlPort > 65535) {
			throw new IllegalArgumentException("Port must be between 0 and 65535");
		}
		if (healthTimeout.isNegative() || healthTimeout.isZero()) {
			throw new IllegalArgumentException("Health timeout must be positive");
		}
		if (handoffPid < 0) {
			throw new IllegalArgumentException("Handoff PID must not be negative");
		}
		if (childCommand.isEmpty()) {
			throw new IllegalArgumentException("Minecraft child command is required after --");
		}
	}

	static RunConfiguration parse(String[] args) throws UnknownHostException {
		Path world = null;
		InetAddress statusBind = InetAddress.getByName("0.0.0.0");
		int statusPort = 25566;
		int controlPort = 0;
		Duration healthTimeout = Duration.ofSeconds(120);
		boolean retry = false;
		long handoffPid = 0L;
		List<String> child = new ArrayList<>();
		for (int index = 0; index < args.length; index++) {
			String argument = args[index];
			if ("--".equals(argument)) {
				for (int childIndex = index + 1; childIndex < args.length; childIndex++) {
					child.add(args[childIndex]);
				}
				break;
			}
			switch (argument) {
				case "--world" -> world = Path.of(requireValue(args, ++index, argument));
				case "--status-bind" -> statusBind = InetAddress.getByName(requireValue(args, ++index, argument));
				case "--status-port" -> statusPort = Integer.parseInt(requireValue(args, ++index, argument));
				case "--control-port" -> controlPort = Integer.parseInt(requireValue(args, ++index, argument));
				case "--health-timeout-seconds" -> healthTimeout = Duration.ofSeconds(
						Long.parseLong(requireValue(args, ++index, argument))
				);
				case "--retry-recovery" -> retry = true;
				case "--handoff-pid" -> handoffPid = Long.parseLong(requireValue(args, ++index, argument));
				default -> throw new IllegalArgumentException("Unknown run option: " + argument);
			}
		}
		if (world == null) {
			throw new IllegalArgumentException("--world is required");
		}
		return new RunConfiguration(world, statusBind, statusPort, controlPort, healthTimeout, retry, handoffPid, child);
	}

	private static String requireValue(String[] args, int index, String option) {
		if (index >= args.length) {
			throw new IllegalArgumentException(option + " requires a value");
		}
		return args[index];
	}
}
