package hardcore_checkpoints.fabric.mixin.client;

import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenDifficultyMixin {
	@Redirect(
			method = "createOnlineButton",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/multiplayer/ClientLevel$ClientLevelData;isHardcore()Z"
			)
	)
	private boolean hardcoreCheckpoints$showDifficultyControlsInHardcore(ClientLevel.ClientLevelData levelData) {
		return false;
	}
}
