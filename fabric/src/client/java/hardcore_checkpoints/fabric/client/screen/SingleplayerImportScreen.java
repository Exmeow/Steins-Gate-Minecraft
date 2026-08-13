package hardcore_checkpoints.fabric.client.screen;

import hardcore_checkpoints.control.transfer.WorldExportImportService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public final class SingleplayerImportScreen extends Screen {
	private final Screen parent;
	private EditBox archivePath;
	private EditBox targetName;
	private volatile Component status = Component.empty();
	private volatile boolean busy;

	public SingleplayerImportScreen(Screen parent) {
		super(Component.translatable("screen.hardcore_checkpoints.import.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		clearWidgets();
		int center = width / 2;
		archivePath = new EditBox(font, center - 150, height / 2 - 36, 300, 20,
				Component.translatable("screen.hardcore_checkpoints.import.archive"));
		archivePath.setHint(Component.translatable("screen.hardcore_checkpoints.import.archive"));
		addRenderableWidget(archivePath);
		targetName = new EditBox(font, center - 150, height / 2 - 8, 300, 20,
				Component.translatable("screen.hardcore_checkpoints.import.target"));
		targetName.setHint(Component.translatable("screen.hardcore_checkpoints.import.target"));
		addRenderableWidget(targetName);
		Button importButton = addRenderableWidget(Button.builder(
				Component.translatable("screen.hardcore_checkpoints.import.action"),
				button -> startImport()
		).bounds(center - 102, height / 2 + 24, 100, 20).build());
		importButton.active = !busy;
		addRenderableWidget(Button.builder(
				Component.translatable("gui.back"),
				button -> minecraft.setScreen(parent)
		).bounds(center + 2, height / 2 + 24, 100, 20).build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(graphics, mouseX, mouseY, partialTick);
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(font, title, width / 2, height / 2 - 72, 0xFFFFFF);
		if (!status.getString().isBlank()) {
			graphics.drawCenteredString(font, status, width / 2, height / 2 + 54, 0xA0A0A0);
		}
	}

	private void startImport() {
		String archive = archivePath.getValue().trim();
		String target = targetName.getValue().trim();
		if (busy || archive.isBlank() || target.isBlank() || target.contains("/") || target.contains("\\")
				|| target.equals(".") || target.equals("..")) {
			status = Component.translatable("screen.hardcore_checkpoints.import.invalid");
			return;
		}
		busy = true;
		String archiveValue = archive;
		String targetValue = target;
		Path saves = minecraft.getLevelSource().getBaseDir().toAbsolutePath().normalize();
		init(minecraft, width, height);
		CompletableFuture.runAsync(() -> {
			try {
				Path targetWorld = saves.resolve(targetValue).normalize();
				if (!targetWorld.startsWith(saves)) {
					throw new IllegalArgumentException("Target world escapes the saves directory");
				}
				new WorldExportImportService(path -> false).importStopped(Path.of(archiveValue), targetWorld);
				status = Component.translatable("screen.hardcore_checkpoints.import.complete", targetValue);
			} catch (Exception exception) {
				status = Component.translatable(
						"screen.hardcore_checkpoints.import.failed",
						trim(String.valueOf(exception.getMessage()), 100)
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
