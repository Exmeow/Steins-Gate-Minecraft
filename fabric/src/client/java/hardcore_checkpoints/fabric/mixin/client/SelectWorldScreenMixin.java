package hardcore_checkpoints.fabric.mixin.client;

import hardcore_checkpoints.fabric.client.screen.SingleplayerImportScreen;
import hardcore_checkpoints.fabric.client.screen.SingleplayerWorldControlScreen;
import hardcore_checkpoints.fabric.singleplayer.SingleplayerActivationIntent;
import hardcore_checkpoints.fabric.singleplayer.SingleplayerWorldTarget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldScreenMixin extends Screen {
	@Shadow
	private WorldSelectionList list;

	@Unique
	private Button hardcoreCheckpoints$manageButton;

	protected SelectWorldScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void hardcoreCheckpoints$addWorldControls(CallbackInfo callback) {
		SingleplayerActivationIntent.clear();
		hardcoreCheckpoints$manageButton = addRenderableWidget(Button.builder(
				Component.translatable("screen.hardcore_checkpoints.singleplayer.manage"),
				button -> openSelectedWorld()
		).bounds(4, 4, 116, 20).build());
		addRenderableWidget(Button.builder(
				Component.translatable("screen.hardcore_checkpoints.import.action"),
				button -> minecraft.setScreen(new SingleplayerImportScreen(this))
		).bounds(124, 4, 92, 20).build());
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void hardcoreCheckpoints$updateWorldControls(
			GuiGraphics graphics,
			int mouseX,
			int mouseY,
			float partialTick,
			CallbackInfo callback
	) {
		if (hardcoreCheckpoints$manageButton != null) {
			hardcoreCheckpoints$manageButton.active = selectedWorld() != null;
		}
	}

	@Unique
	private void openSelectedWorld() {
		WorldSelectionList.WorldListEntry entry = selectedWorld();
		if (entry == null) {
			return;
		}
		var summary = ((WorldListEntryAccessor) (Object) entry).hardcoreCheckpoints$summary();
		var target = SingleplayerWorldTarget.from(
				minecraft.getLevelSource().getBaseDir(),
				summary.getLevelId(),
				summary.getLevelName()
		);
		minecraft.setScreen(new SingleplayerWorldControlScreen(
				this,
				target.worldRoot(),
				target.displayName(),
				summary.isHardcore()
		));
	}

	@Unique
	private WorldSelectionList.WorldListEntry selectedWorld() {
		return list.getSelectedOpt()
				.filter(WorldSelectionList.WorldListEntry.class::isInstance)
				.map(WorldSelectionList.WorldListEntry.class::cast)
				.orElse(null);
	}
}
