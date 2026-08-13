package hardcore_checkpoints;

import hardcore_checkpoints.registry.ModBlockEntities;
import hardcore_checkpoints.registry.ModBlocks;
import net.blay09.mods.balm.api.module.BalmModule;
import net.blay09.mods.balm.world.level.block.BalmBlockRegistrar;
import net.blay09.mods.balm.world.level.block.entity.BalmBlockEntityTypeRegistrar;
import net.minecraft.resources.ResourceLocation;

public final class HardcoreCheckpointsModule implements BalmModule {
	public static final HardcoreCheckpointsModule INSTANCE = new HardcoreCheckpointsModule();

	private HardcoreCheckpointsModule() {
	}

	@Override
	public ResourceLocation getId() {
		return HardcoreCheckpoints.id("core");
	}

	@Override
	public void registerBlocks(BalmBlockRegistrar blocks) {
		ModBlocks.register(blocks);
	}

	@Override
	public void registerBlockEntityTypes(BalmBlockEntityTypeRegistrar blockEntityTypes) {
		ModBlockEntities.register(blockEntityTypes);
	}
}
