package hardcore_checkpoints.fabric;

import hardcore_checkpoints.HardcoreCheckpoints;
import hardcore_checkpoints.HardcoreCheckpointsModule;
import hardcore_checkpoints.fabric.checkpoint.FabricCheckpointCaptureIntegration;
import hardcore_checkpoints.fabric.command.DedicatedServerCommands;
import hardcore_checkpoints.fabric.network.FabricCheckpointNetworking;
import hardcore_checkpoints.fabric.recovery.FabricDeathRecoveryIntegration;
import hardcore_checkpoints.fabric.platform.FabricCheckpointCoreLifecycle;
import hardcore_checkpoints.fabric.platform.FabricCreativeTabIntegration;
import hardcore_checkpoints.fabric.platform.FabricGameplayFreezeController;
import hardcore_checkpoints.fabric.platform.FabricWorldControlIntegration;
import hardcore_checkpoints.fabric.supervisor.FabricSupervisorBridge;
import hardcore_checkpoints.fabric.effect.FabricGlobalEffects;
import hardcore_checkpoints.registry.ModSounds;
import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.EmptyLoadContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class HardcoreCheckpointsFabric implements ModInitializer {
	@Override
	public void onInitialize() {
		Balm.initializeMod(
				HardcoreCheckpoints.MOD_ID,
				EmptyLoadContext.INSTANCE,
				HardcoreCheckpointsModule.INSTANCE
		);
		ModSounds.register();
		FabricGlobalEffects.initialize();
		DedicatedServerCommands.initialize();
		FabricCheckpointNetworking.initialize();
		FabricCheckpointCoreLifecycle.initialize();
		FabricCreativeTabIntegration.initialize();
		FabricWorldControlIntegration.initialize();
		FabricDeathRecoveryIntegration.initialize();
		FabricSupervisorBridge.initialize();
		FabricGameplayFreezeController.initialize();
		FabricCheckpointCaptureIntegration.initialize();

		String version = FabricLoader.getInstance()
				.getModContainer(HardcoreCheckpoints.MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
		HardcoreCheckpoints.LOGGER.info(
				"{} {} initialized on Fabric (mod id: {})",
				HardcoreCheckpoints.MOD_NAME,
				version,
				HardcoreCheckpoints.MOD_ID
		);
	}
}
