package hardcore_checkpoints;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HardcoreCheckpoints {
	public static final String MOD_ID = "hardcore_checkpoints";
	public static final String MOD_NAME = "Hardcore Checkpoints";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private HardcoreCheckpoints() {
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
