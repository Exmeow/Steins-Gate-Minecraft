package hardcore_checkpoints.fabric.mixin.client;

import hardcore_checkpoints.control.repository.WorldControlBootstrap;
import hardcore_checkpoints.fabric.client.screen.SingleplayerRecoveryScreen;
import hardcore_checkpoints.fabric.client.screen.SingleplayerWorldControlScreen;
import hardcore_checkpoints.recovery.DeathTransactionRepository;
import hardcore_checkpoints.fabric.singleplayer.SingleplayerWorldTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class WorldListEntryJoinGuardMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	@Final
	private SelectWorldScreen screen;


	@Inject(method = "joinWorld", at = @At("HEAD"), cancellable = true)
	private void hardcoreCheckpoints$rejectIncompleteWorld(CallbackInfo callback) {
		var summary = ((WorldListEntryAccessor) this).hardcoreCheckpoints$summary();
		var target = SingleplayerWorldTarget.from(
				minecraft.getLevelSource().getBaseDir(),
				summary.getLevelId(),
				summary.getLevelName()
		);
		var worldRoot = target.worldRoot();
		var result = new WorldControlBootstrap().inspect(worldRoot);
		if (result.optionalLayout().isPresent()) {
			try {
				if (new DeathTransactionRepository().findActive(result.optionalLayout().orElseThrow()).isPresent()) {
					minecraft.setScreen(new SingleplayerRecoveryScreen(
							screen,
							result.optionalLayout().orElseThrow(),
							summary.getLevelId(),
							target.displayName(),
							true
					));
					callback.cancel();
					return;
				}
			} catch (Exception exception) {
				minecraft.setScreen(new SingleplayerWorldControlScreen(
						screen,
						worldRoot,
						target.displayName(),
						summary.isHardcore()
				));
				callback.cancel();
				return;
			}
		}
		if (result.status() != WorldControlBootstrap.BootstrapStatus.INCOMPLETE_CONTROL_DATA) {
			return;
		}
		minecraft.setScreen(new SingleplayerWorldControlScreen(
				screen,
				worldRoot,
				target.displayName(),
				summary.isHardcore()
		));
		callback.cancel();
	}
}
