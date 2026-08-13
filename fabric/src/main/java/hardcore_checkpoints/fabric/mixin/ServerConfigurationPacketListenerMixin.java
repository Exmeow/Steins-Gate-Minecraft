package hardcore_checkpoints.fabric.mixin;

import hardcore_checkpoints.fabric.network.FabricCheckpointNetworking;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ServerConfigurationPacketListenerMixin {
	@Inject(method = "returnToWorld", at = @At("HEAD"))
	private void hardcoreCheckpoints$configureReturnToWaiting(CallbackInfo callback) {
		FabricCheckpointNetworking.configureReconfiguration(
				(ServerConfigurationPacketListenerImpl) (Object) this
		);
	}
}
