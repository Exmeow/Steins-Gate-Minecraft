package hardcore_checkpoints.fabric.client.screen;

import hardcore_checkpoints.fabric.client.DedicatedRecoveryController;
import hardcore_checkpoints.fabric.client.DedicatedRecoveryMusic;
import hardcore_checkpoints.supervisor.protocol.SupervisorStatusResponse;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

public final class DedicatedRecoveryScreen extends Screen {
	public DedicatedRecoveryScreen() {
		super(Component.translatable("screen.hardcore_checkpoints.dedicated_recovery.title"));
	}

	@Override
	protected void init() {
		DedicatedRecoveryMusic.start(minecraft);
		clearWidgets();
		addRenderableWidget(Button.builder(
				Component.translatable("screen.hardcore_checkpoints.dedicated_recovery.cancel"),
				button -> DedicatedRecoveryController.cancel(minecraft)
		).bounds(width / 2 - 100, height - 28, 200, 20).build());
	}

	@Override
	public void tick() {
		DedicatedRecoveryMusic.start(minecraft);
		super.tick();
	}
	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(graphics, mouseX, mouseY, partialTick);
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(font, title, width / 2, height / 2 - 54, 0xFFFFFF);
		SupervisorStatusResponse status = DedicatedRecoveryController.status();
		if (status == null) {
			graphics.drawCenteredString(
					font,
					Component.translatable("screen.hardcore_checkpoints.dedicated_recovery.contacting"),
					width / 2,
					height / 2 - 18,
					0xA0A0A0
			);
			return;
		}
		Component phase = Component.translatable(
				"screen.hardcore_checkpoints.supervisor_phase." + status.phase().toLowerCase(Locale.ROOT)
		);
		graphics.drawCenteredString(font, phase, width / 2, height / 2 - 24, 0xE0E0E0);
		if (status.progress() > 0.0 && status.progress() < 1.0) {
			graphics.drawCenteredString(
					font,
					Component.translatable(
							"screen.hardcore_checkpoints.dedicated_recovery.progress",
							(int) Math.round(status.progress() * 100.0)
					),
					width / 2,
					height / 2 - 6,
					0xA0A0A0
			);
		}
		if (status.errorMessage() != null) {
			graphics.drawCenteredString(
					font,
					Component.literal(trim(status.errorMessage(), 100)),
					width / 2,
					height / 2 + 14,
					0xFF8080
			);
		}
	}

	@Override
	public void removed() {
		DedicatedRecoveryMusic.stop(minecraft);
		super.removed();
	}
	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static String trim(String value, int maximum) {
		return value.length() <= maximum ? value : value.substring(0, maximum - 3) + "...";
	}
}
