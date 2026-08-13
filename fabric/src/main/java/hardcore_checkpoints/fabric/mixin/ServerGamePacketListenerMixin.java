package hardcore_checkpoints.fabric.mixin;

import hardcore_checkpoints.fabric.network.FabricCheckpointNetworking;
import hardcore_checkpoints.fabric.platform.FabricGameplayFreezeController;
import net.fabricmc.fabric.mixin.networking.accessor.ServerCommonNetworkHandlerAccessor;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
	@Inject(
			method = {
					"handleMovePlayer",
					"handleMoveVehicle",
					"handlePlayerAction",
					"handlePlayerCommand",
					"handleUseItemOn",
					"handleUseItem",
					"handleInteract",
					"handleAnimate",
					"handleSetCarriedItem",
					"handleContainerClick",
					"handleContainerButtonClick",
					"handleSetCreativeModeSlot",
					"handleRenameItem",
					"handlePlaceRecipe",
					"handlePickItem",
					"handleEditBook",
					"handleSignUpdate",
					"handleSetCommandBlock",
					"handleSetStructureBlock",
					"handleSetJigsawBlock",
					"handleJigsawGenerate"
			},
			at = @At("HEAD"),
			cancellable = true
	)
	private void hardcoreCheckpoints$gateGameplayPackets(CallbackInfo callback) {
		if (!FabricGameplayFreezeController.allowsGameplay()) {
			callback.cancel();
		}
	}

	@Inject(method = "handleConfigurationAcknowledged", at = @At("RETURN"))
	private void hardcoreCheckpoints$startReturnToWaitingConfiguration(
			ServerboundConfigurationAcknowledgedPacket packet,
			CallbackInfo callback
	) {
		ServerCommonNetworkHandlerAccessor handler = (ServerCommonNetworkHandlerAccessor) this;
		if (handler.getConnection().getPacketListener() instanceof ServerConfigurationPacketListenerImpl configuration
				&& FabricCheckpointNetworking.configureReconfiguration(configuration)) {
			configuration.startConfiguration();
		}
	}
}
