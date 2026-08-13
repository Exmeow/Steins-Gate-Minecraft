package hardcore_checkpoints.registry;

import hardcore_checkpoints.block.CheckpointCoreBlock;
import hardcore_checkpoints.item.CheckpointCoreItem;
import net.blay09.mods.balm.world.level.block.BalmBlockRegistrar;
import net.blay09.mods.balm.world.level.block.DeferredBlock;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import java.util.Objects;

public final class ModBlocks {
	private static DeferredBlock checkpointCore;

	private ModBlocks() {
	}

	public static void register(BalmBlockRegistrar blocks) {
		if (checkpointCore != null) {
			throw new IllegalStateException("Blocks already registered");
		}

		checkpointCore = blocks.register(
				"checkpoint_core",
				CheckpointCoreBlock::new,
				properties -> properties
						.mapColor(MapColor.COLOR_PURPLE)
						.strength(0.8F, 3.0F)
						.sound(SoundType.GLASS)
						.noOcclusion()
						.lightLevel(state -> state.getValue(CheckpointCoreBlock.LIT) ? 15 : 0)
						.pushReaction(PushReaction.BLOCK)
						.isRedstoneConductor((state, level, pos) -> false)
						.isSuffocating((state, level, pos) -> false)
						.isViewBlocking((state, level, pos) -> false)
		).withItem(
				CheckpointCoreItem::new,
				properties -> properties.stacksTo(64).rarity(Rarity.COMMON)
		).asDeferredBlock();
	}

	public static DeferredBlock checkpointCore() {
		return Objects.requireNonNull(checkpointCore, "Checkpoint core has not been registered");
	}
}
