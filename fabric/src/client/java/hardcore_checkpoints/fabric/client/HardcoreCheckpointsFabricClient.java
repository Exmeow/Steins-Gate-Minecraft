package hardcore_checkpoints.fabric.client;

import hardcore_checkpoints.registry.ModBlocks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.renderer.RenderType;

public final class HardcoreCheckpointsFabricClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		FabricCheckpointNetworkingClient.initialize();
		SingleplayerRecoveryController.initialize();
		DedicatedRecoveryController.initialize();
		FabricClientFreezeController.initialize();
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.checkpointCore().value(), RenderType.cutout());
	}
}
