package hardcore_checkpoints.fabric.mixin.client;

import hardcore_checkpoints.fabric.singleplayer.SingleplayerActivationIntent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldCheckpointToggleMixin extends Screen {
	@Shadow
	@Final
	private WorldCreationUiState uiState;

	@Unique
	private Button hardcoreCheckpoints$toggle;

	@Unique
	private boolean hardcoreCheckpoints$enabled;

	@Unique
	private boolean hardcoreCheckpoints$creating;

	protected CreateWorldCheckpointToggleMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void hardcoreCheckpoints$addToggle(CallbackInfo callback) {
		hardcoreCheckpoints$toggle = addRenderableWidget(Button.builder(
				label(),
				button -> {
					hardcoreCheckpoints$enabled = !hardcoreCheckpoints$enabled;
					button.setMessage(label());
				}
		).bounds(Math.max(4, width - 164), 4, 160, 20).build());
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void hardcoreCheckpoints$updateToggle(
			GuiGraphics graphics,
			int mouseX,
			int mouseY,
			float partialTick,
			CallbackInfo callback
	) {
		if (hardcoreCheckpoints$toggle != null) {
			hardcoreCheckpoints$toggle.visible = uiState.isHardcore();
			hardcoreCheckpoints$toggle.active = uiState.isHardcore();
		}
	}

	@Inject(method = "onCreate", at = @At("HEAD"))
	private void hardcoreCheckpoints$rememberCreationIntent(CallbackInfo callback) {
		hardcoreCheckpoints$creating = true;
		SingleplayerActivationIntent.setEnabled(uiState.isHardcore() && hardcoreCheckpoints$enabled);
	}

	@Inject(method = "popScreen", at = @At("HEAD"))
	private void hardcoreCheckpoints$clearAbandonedIntent(CallbackInfo callback) {
		if (!hardcoreCheckpoints$creating) {
			SingleplayerActivationIntent.clear();
		}
	}

	@Unique
	private Component label() {
		return Component.translatable(hardcoreCheckpoints$enabled
				? "screen.hardcore_checkpoints.create.enabled"
				: "screen.hardcore_checkpoints.create.disabled");
	}
}
