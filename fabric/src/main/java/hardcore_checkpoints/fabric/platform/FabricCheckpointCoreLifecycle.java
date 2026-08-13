package hardcore_checkpoints.fabric.platform;

import hardcore_checkpoints.block.CheckpointCoreBlock;
import hardcore_checkpoints.block.entity.CheckpointCoreBlockEntity;
import hardcore_checkpoints.control.WorldControlRuntime;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.world.level.block.Block;

public final class FabricCheckpointCoreLifecycle {
	private FabricCheckpointCoreLifecycle() {
	}

	public static void initialize() {
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			boolean enabled = WorldControlRuntime.current()
					.flatMap(result -> result.optionalMetadata())
					.map(metadata -> metadata.featureEnabled())
					.orElse(false);
			if (enabled) {
				return;
			}
			for (var blockEntity : chunk.getBlockEntities().values()) {
				if (blockEntity instanceof CheckpointCoreBlockEntity core
						&& core.getBlockState().getValue(CheckpointCoreBlock.LIT)) {
					level.setBlock(
							core.getBlockPos(),
							core.getBlockState().setValue(CheckpointCoreBlock.LIT, false),
							Block.UPDATE_ALL
					);
				}
			}
		});
	}
}
