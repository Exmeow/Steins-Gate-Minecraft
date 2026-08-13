package hardcore_checkpoints.supervisor;

public final class ExitZeroChild {
	private ExitZeroChild() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length == 2 && "sleep".equals(args[0])) {
			Thread.sleep(Long.parseLong(args[1]));
			return;
		}
		if (args.length == 2 && "mark".equals(args[0])) {
			java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]), "started");
		}
	}
}
