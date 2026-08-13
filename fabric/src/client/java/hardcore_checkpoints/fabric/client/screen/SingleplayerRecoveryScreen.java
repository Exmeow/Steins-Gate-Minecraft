package hardcore_checkpoints.fabric.client.screen;

import hardcore_checkpoints.HardcoreCheckpoints;
import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.recovery.DeathTransactionRepository;
import hardcore_checkpoints.recovery.DeathTransactionStage;
import hardcore_checkpoints.recovery.OfflineRollbackService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

public final class SingleplayerRecoveryScreen extends Screen {
	private final Screen parent;
	private final ControlRootLayout layout;
	private final String levelId;
	private final String displayName;
	private volatile Component status;
	private volatile boolean busy;
	private volatile boolean failed;
	private boolean autoStart;

	public SingleplayerRecoveryScreen(
			Screen parent,
			ControlRootLayout layout,
			String levelId,
			String displayName,
			boolean autoStart
	) {
		super(Component.translatable("screen.hardcore_checkpoints.recovery.title"));
		this.parent = parent;
		this.layout = layout;
		this.levelId = levelId;
		this.displayName = displayName;
		this.autoStart = autoStart;
		this.status = Component.translatable("screen.hardcore_checkpoints.recovery.pending");
		this.failed = detectFailure();
	}

	@Override
	protected void init() {
		clearWidgets();
		int center = width / 2;
		if (failed && !busy) {
			addRenderableWidget(Button.builder(
					Component.translatable("screen.hardcore_checkpoints.recovery.retry"),
					button -> startRecovery(true)
			).bounds(center - 100, height / 2 + 26, 200, 20).build());
		}
		if (!busy) {
			addRenderableWidget(Button.builder(
					Component.translatable("gui.back"),
					button -> minecraft.setScreen(parent == null ? new TitleScreen() : parent)
			).bounds(center - 100, height - 28, 200, 20).build());
		}
		if (autoStart && !busy && !failed) {
			autoStart = false;
			startRecovery(false);
		}
	}

	private boolean detectFailure() {
		try {
			return new DeathTransactionRepository().findActive(layout)
					.map(transaction -> transaction.stage() == DeathTransactionStage.RECOVERY_FAILED)
					.orElse(false);
		} catch (Exception exception) {
			HardcoreCheckpoints.LOGGER.error("Failed to inspect singleplayer death recovery", exception);
			status = Component.translatable(
					"screen.hardcore_checkpoints.recovery.failed",
					trim(String.valueOf(exception.getMessage()), 100)
			);
			return true;
		}
	}

	private void startRecovery(boolean retry) {
		if (busy) {
			return;
		}
		busy = true;
		failed = false;
		status = Component.translatable("screen.hardcore_checkpoints.recovery.restoring");
		init(minecraft, width, height);
		CompletableFuture.runAsync(() -> {
			try {
				OfflineRollbackService service = new OfflineRollbackService(
						world -> minecraft.getSingleplayerServer() != null
				);
				if (retry) {
					service.retryStopped(layout, Instant.now());
				} else {
					service.restoreStopped(layout, Instant.now());
				}
				minecraft.execute(() -> {
					status = Component.translatable("screen.hardcore_checkpoints.recovery.reopening");
					minecraft.createWorldOpenFlows().openWorld(levelId, () ->
							minecraft.setScreen(parent == null ? new TitleScreen() : parent));
				});
			} catch (Exception exception) {
				HardcoreCheckpoints.LOGGER.error("Singleplayer checkpoint recovery failed", exception);
				minecraft.execute(() -> {
					busy = false;
					failed = true;
					status = Component.translatable(
							"screen.hardcore_checkpoints.recovery.failed",
							trim(String.valueOf(exception.getMessage()), 100)
					);
					init(minecraft, width, height);
				});
			}
		});
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(graphics, mouseX, mouseY, partialTick);
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(font, title, width / 2, 30, 0xFFFFFF);
		graphics.drawCenteredString(font, Component.literal(displayName), width / 2, 50, 0xA0A0A0);
		graphics.drawCenteredString(font, status, width / 2, height / 2 - 10, failed ? 0xFF8080 : 0xFFFFFF);
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
		if (value == null) {
			return "unknown";
		}
		return value.length() <= maximum ? value : value.substring(0, maximum - 3) + "...";
	}
}
