package hardcore_checkpoints.fabric.client.screen;

import hardcore_checkpoints.control.repository.ControlStateRepository;
import hardcore_checkpoints.control.repository.WorldControlBootstrap;
import hardcore_checkpoints.control.transfer.WorldExportImportService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class SingleplayerWorldControlScreen extends Screen {
	private final Screen parent;
	private final Path worldRoot;
	private final String worldName;
	private final boolean hardcore;
	private final ControlStateRepository repository = new ControlStateRepository();
	private final WorldControlBootstrap bootstrap = new WorldControlBootstrap(repository);
	private WorldControlBootstrap.BootstrapResult controlState;
	private volatile Component status = Component.empty();
	private volatile boolean busy;

	public SingleplayerWorldControlScreen(Screen parent, Path worldRoot, String worldName, boolean hardcore) {
		super(Component.translatable("screen.hardcore_checkpoints.singleplayer.title"));
		this.parent = parent;
		this.worldRoot = worldRoot.toAbsolutePath().normalize();
		this.worldName = worldName;
		this.hardcore = hardcore;
	}

	@Override
	protected void init() {
		clearWidgets();
		controlState = bootstrap.inspect(worldRoot);
		WorldControlBootstrap.BootstrapResult result = controlState;
		int center = width / 2;
		int y = height / 2 - 12;
		Button enable = addRenderableWidget(Button.builder(
				Component.translatable("screen.hardcore_checkpoints.singleplayer.enable"),
				button -> enable()
		).bounds(center - 154, y, 100, 20).build());
		Button disable = addRenderableWidget(Button.builder(
				Component.translatable("screen.hardcore_checkpoints.singleplayer.disable"),
				button -> disable()
		).bounds(center - 50, y, 100, 20).build());
		Button export = addRenderableWidget(Button.builder(
				Component.translatable("screen.hardcore_checkpoints.singleplayer.export"),
				button -> export()
		).bounds(center + 54, y, 100, 20).build());
		enable.active = !busy && hardcore && (result.status() == WorldControlBootstrap.BootstrapStatus.UNINITIALIZED_DISABLED
				|| result.status() == WorldControlBootstrap.BootstrapStatus.FEATURE_DISABLED);
		boolean missingControlRoot = result.status() == WorldControlBootstrap.BootstrapStatus.INCOMPLETE_CONTROL_DATA
				&& result.optionalLayout().isPresent()
				&& !Files.exists(result.optionalLayout().orElseThrow().instanceRoot());
		disable.active = !busy && (result.status() == WorldControlBootstrap.BootstrapStatus.CONTROL_READY || missingControlRoot);
		export.active = !busy && result.optionalLayout().isPresent() && result.optionalMetadata().isPresent();
		addRenderableWidget(Button.builder(
				Component.translatable("gui.back"),
				button -> minecraft.setScreen(parent)
		).bounds(center - 50, y + 28, 100, 20).build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(graphics, mouseX, mouseY, partialTick);
		super.render(graphics, mouseX, mouseY, partialTick);
		WorldControlBootstrap.BootstrapResult result = controlState;
		graphics.drawCenteredString(font, title, width / 2, height / 2 - 76, 0xFFFFFF);
		graphics.drawCenteredString(font, Component.literal(worldName), width / 2, height / 2 - 56, 0xE0E0E0);
		graphics.drawCenteredString(
				font,
				Component.translatable("screen.hardcore_checkpoints.control_status."
						+ result.status().name().toLowerCase(Locale.ROOT)),
				width / 2,
				height / 2 - 38,
				result.status() == WorldControlBootstrap.BootstrapStatus.INCOMPLETE_CONTROL_DATA ? 0xFF5555 : 0xA0A0A0
		);
		Component detail = busy
				? Component.translatable("screen.hardcore_checkpoints.singleplayer.working")
				: !status.getString().isBlank()
						? status
						: Component.literal(trim(String.join("; ", result.issues()), 100));
		if (!detail.getString().isBlank()) {
			graphics.drawCenteredString(font, detail, width / 2, height / 2 + 50, 0xA0A0A0);
		}
	}

	private void enable() {
		if (!hardcore || busy) {
			return;
		}
		minecraft.setScreen(new SingleplayerEnableProgressScreen(this, worldRoot));
	}

	private void disable() {
		if (busy) {
			return;
		}
		try {
			var result = bootstrap.inspect(worldRoot);
			if (result.status() == WorldControlBootstrap.BootstrapStatus.INCOMPLETE_CONTROL_DATA) {
				repository.abandonMissingControlRoot(worldRoot);
			} else {
				repository.disable(result.optionalLayout().orElseThrow());
			}
			status = Component.translatable("screen.hardcore_checkpoints.singleplayer.disabled");
			init(minecraft, width, height);
		} catch (Exception exception) {
			status = Component.translatable(
					"screen.hardcore_checkpoints.singleplayer.disable_failed",
					trim(String.valueOf(exception.getMessage()), 80)
			);
		}
	}

	private void export() {
		if (busy) {
			return;
		}
		busy = true;
		status = Component.empty();
		init(minecraft, width, height);
		CompletableFuture.runAsync(() -> {
			try {
				Path exports = worldRoot.getParent().resolve(".hardcore_checkpoints").resolve("exports");
				Files.createDirectories(exports);
				String timestamp = Instant.now().toString().replace(':', '-');
				Path archive = exports.resolve(worldName + "-" + timestamp + ".zip");
				new WorldExportImportService(path -> false).exportStopped(worldRoot, archive);
				status = Component.translatable(
						"screen.hardcore_checkpoints.singleplayer.exported",
						trim(archive.toString(), 80)
				);
			} catch (Exception exception) {
				status = Component.translatable(
						"screen.hardcore_checkpoints.singleplayer.export_failed",
						trim(String.valueOf(exception.getMessage()), 80)
				);
			} finally {
				busy = false;
				minecraft.execute(() -> init(minecraft, width, height));
			}
		});
	}

	private static String trim(String value, int maxLength) {
		return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
	}
}
