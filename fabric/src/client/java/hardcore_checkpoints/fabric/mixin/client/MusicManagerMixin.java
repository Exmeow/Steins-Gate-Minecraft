package hardcore_checkpoints.fabric.mixin.client;

import hardcore_checkpoints.fabric.client.DedicatedRecoveryMusic;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void hardcoreCheckpoints$pauseVanillaMusicDuringRecovery(CallbackInfo callback) {
		if (DedicatedRecoveryMusic.isControllingMusic()) {
			callback.cancel();
		}
	}
}
