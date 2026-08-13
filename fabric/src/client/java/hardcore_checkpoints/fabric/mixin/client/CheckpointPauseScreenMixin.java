package hardcore_checkpoints.fabric.mixin.client;

import hardcore_checkpoints.fabric.client.screen.CheckpointStatusScreen;
import hardcore_checkpoints.fabric.client.state.CheckpointClientState;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class CheckpointPauseScreenMixin extends Screen {
	protected CheckpointPauseScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "createPauseMenu", at = @At("TAIL"))
	private void hardcoreCheckpoints$addStatusButton(CallbackInfo callback) {
		var snapshot = CheckpointClientState.snapshot().orElse(null);
		if (snapshot == null || !snapshot.rosterMember() || CheckpointClientState.isConfiguring()) {
			return;
		}
		addRenderableWidget(Button.builder(
				Component.translatable("screen.hardcore_checkpoints.pause.open"),
				button -> minecraft.setScreen(new CheckpointStatusScreen())
		).bounds(Math.max(4, width - 108), 4, 104, 20).build());
	}
}
