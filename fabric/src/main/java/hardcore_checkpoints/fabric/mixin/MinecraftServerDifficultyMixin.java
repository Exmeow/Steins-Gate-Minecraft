package hardcore_checkpoints.fabric.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.WorldData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerDifficultyMixin {
	@Redirect(
			method = "setDifficulty",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/level/storage/WorldData;isHardcore()Z"
			)
	)
	private boolean hardcoreCheckpoints$doNotForceHardDifficulty(WorldData worldData) {
		return false;
	}
}
