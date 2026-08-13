package hardcore_checkpoints.fabric.client.screen;

import hardcore_checkpoints.control.repository.ControlStateRepository;
import hardcore_checkpoints.control.repository.WorldControlBootstrap;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SingleplayerEnableProgressScreen extends Screen {
	private final SingleplayerWorldControlScreen parent;
	private final Path worldRoot;
	private final AtomicBoolean cancelled = new AtomicBoolean();

	private volatile Stage stage = Stage.PRECHECK;
	private volatile String detail = "";
	private boolean started;

	public SingleplayerEnableProgressScreen(SingleplayerWorldControlScreen parent, Path worldRoot) {
		super(Component.translatable("screen.hardcore_checkpoints.enable_progress.title"));
		this.parent = parent;
		this.worldRoot = worldRoot.toAbsolutePath().normalize();
	}

	@Override
	protected void init() {
		clearWidgets();
		Button action = addRenderableWidget(Button.builder(
				stage == Stage.COMPLETE || stage == Stage.FAILED
						? Component.translatable("gui.back")
						: Component.translatable("gui.cancel"),
				button -> {
					if (stage == Stage.PRECHECK) {
						cancelled.set(true);
						minecraft.setScreen(parent);
					} else if (stage == Stage.COMPLETE || stage == Stage.FAILED) {
						minecraft.setScreen(parent);
					}
				}
		).bounds(width / 2 - 50, height / 2 + 30, 100, 20).build());
		action.active = stage == Stage.PRECHECK || stage == Stage.COMPLETE || stage == Stage.FAILED;
		if (!started) {
			started = true;
			startWork();
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(graphics, mouseX, mouseY, partialTick);
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(font, title, width / 2, height / 2 - 45, 0xFFFFFF);
		graphics.drawCenteredString(font, Component.translatable(stage.translationKey), width / 2, height / 2 - 15, 0xE0E0E0);
		if (!detail.isBlank()) {
			graphics.drawCenteredString(font, Component.literal(trim(detail, 100)), width / 2, height / 2 + 4, 0xA0A0A0);
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return stage == Stage.PRECHECK || stage == Stage.COMPLETE || stage == Stage.FAILED;
	}

	@Override
	public void onClose() {
		if (stage == Stage.PRECHECK) {
			cancelled.set(true);
		}
		if (stage != Stage.COMMITTING) {
			minecraft.setScreen(parent);
		}
	}

	private void startWork() {
		CompletableFuture.runAsync(() -> {
			ControlStateRepository repository = new ControlStateRepository();
			WorldControlBootstrap bootstrap = new WorldControlBootstrap(repository);
			try {
				WorldControlBootstrap.BootstrapResult result = bootstrap.inspect(worldRoot);
				if (result.status() != WorldControlBootstrap.BootstrapStatus.UNINITIALIZED_DISABLED
						&& result.status() != WorldControlBootstrap.BootstrapStatus.FEATURE_DISABLED) {
					throw new IllegalStateException("World control status is " + result.status());
				}
				if (cancelled.get()) {
					return;
				}
				stage = Stage.COMMITTING;
				refresh();
				var layout = result.optionalLayout().isPresent()
						? result.optionalLayout().orElseThrow()
						: repository.initializeDisabled(worldRoot).layout();
				repository.startNewActivation(layout);
				stage = Stage.COMPLETE;
				detail = "";
			} catch (Exception exception) {
				stage = Stage.FAILED;
				detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
			}
			refresh();
		});
	}

	private void refresh() {
		if (minecraft != null) {
			minecraft.execute(() -> init(minecraft, width, height));
		}
	}

	private static String trim(String value, int maxLength) {
		return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
	}

	private enum Stage {
		PRECHECK("screen.hardcore_checkpoints.enable_progress.precheck"),
		COMMITTING("screen.hardcore_checkpoints.enable_progress.committing"),
		COMPLETE("screen.hardcore_checkpoints.enable_progress.complete"),
		FAILED("screen.hardcore_checkpoints.enable_progress.failed");

		private final String translationKey;

		Stage(String translationKey) {
			this.translationKey = translationKey;
		}
	}
}
