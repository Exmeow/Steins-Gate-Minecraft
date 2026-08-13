package hardcore_checkpoints.control.transfer;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface WorldActivityProbe {
	boolean isWorldRunning(Path worldRoot) throws IOException;
}
