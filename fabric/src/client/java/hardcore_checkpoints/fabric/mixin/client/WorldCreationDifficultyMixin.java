package hardcore_checkpoints.fabric.mixin.client;

import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.Difficulty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldCreationUiState.class)
public abstract class WorldCreationDifficultyMixin {
	@Shadow
	private Difficulty difficulty;

	@Inject(method = "getDifficulty", at = @At("HEAD"), cancellable = true)
	private void hardcoreCheckpoints$keepSelectedHardcoreDifficulty(CallbackInfoReturnable<Difficulty> callback) {
		callback.setReturnValue(difficulty);
	}
}
