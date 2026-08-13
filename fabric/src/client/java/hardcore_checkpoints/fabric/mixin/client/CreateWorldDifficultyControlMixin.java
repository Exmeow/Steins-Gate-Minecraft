package hardcore_checkpoints.fabric.mixin.client;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.Difficulty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$GameTab")
public abstract class CreateWorldDifficultyControlMixin {
	@Inject(method = "method_48664", at = @At("TAIL"))
	private void hardcoreCheckpoints$allowHardcoreDifficultySelection(
			CycleButton<Difficulty> button,
			WorldCreationUiState state,
			CallbackInfo callback
	) {
		button.active = !state.isDebug();
	}
}
