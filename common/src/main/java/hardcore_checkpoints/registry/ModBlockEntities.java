package hardcore_checkpoints.registry;

import hardcore_checkpoints.block.entity.CheckpointCoreBlockEntity;
import net.blay09.mods.balm.world.level.block.entity.BalmBlockEntityTypeRegistrar;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Objects;
import java.util.function.Supplier;

public final class ModBlockEntities {
	private static Supplier<BlockEntityType<CheckpointCoreBlockEntity>> checkpointCore;

	private ModBlockEntities() {
	}

	public static void register(BalmBlockEntityTypeRegistrar blockEntityTypes) {
		if (checkpointCore != null) {
			throw new IllegalStateException("Block entities already registered");
		}

		checkpointCore = blockEntityTypes.register(
				"checkpoint_core",
				CheckpointCoreBlockEntity::new,
				ModBlocks.checkpointCore()
		).asSupplier();
	}

	public static BlockEntityType<CheckpointCoreBlockEntity> checkpointCore() {
		return Objects.requireNonNull(checkpointCore, "Checkpoint core block entity has not been registered").get();
	}
}
