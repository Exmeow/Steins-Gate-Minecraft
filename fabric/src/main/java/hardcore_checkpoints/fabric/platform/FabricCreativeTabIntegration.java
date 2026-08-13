package hardcore_checkpoints.fabric.platform;

import hardcore_checkpoints.registry.ModBlocks;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;

public final class FabricCreativeTabIntegration {
	private static final ResourceKey<CreativeModeTab> FUNCTIONAL_BLOCKS = ResourceKey.create(
			Registries.CREATIVE_MODE_TAB,
			ResourceLocation.withDefaultNamespace("functional_blocks")
	);

	private FabricCreativeTabIntegration() {
	}

	public static void initialize() {
		ItemGroupEvents.modifyEntriesEvent(FUNCTIONAL_BLOCKS)
				.register(entries -> entries.accept(ModBlocks.checkpointCore()));
	}
}
