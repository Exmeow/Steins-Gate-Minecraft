package hardcore_checkpoints.fabric.client.screen;

import hardcore_checkpoints.fabric.client.state.CheckpointClientState;
import hardcore_checkpoints.server.network.ControlAction;
import hardcore_checkpoints.server.network.ControlStateSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class CheckpointRosterScreen extends Screen {
	private static final int ROWS_PER_PAGE = 6;

	private final int page;
	private List<Row> rows = List.of();

	public CheckpointRosterScreen(int page) {
		super(Component.translatable("screen.hardcore_checkpoints.roster.title"));
		this.page = Math.max(0, page);
	}

	@Override
	protected void init() {
		clearWidgets();
		addRenderableWidget(Button.builder(
				CheckpointScreenActions.exitLabel(minecraft),
				button -> CheckpointScreenActions.disconnect(minecraft)
		).bounds(width / 2 - 100, height - 28, 200, 20).build());
		ControlStateSnapshot snapshot = CheckpointClientState.snapshot().orElse(null);
		if (snapshot == null || !snapshot.administrator()) {
			return;
		}
		List<Row> available = new ArrayList<>();
		for (ControlStateSnapshot.RosterEntry request : snapshot.joinRequests()) {
			available.add(new Row(request, true));
		}
		for (ControlStateSnapshot.RosterEntry member : snapshot.roster()) {
			available.add(new Row(member, false));
		}
		int maxPage = Math.max(0, (available.size() - 1) / ROWS_PER_PAGE);
		int actualPage = Math.min(page, maxPage);
		int from = actualPage * ROWS_PER_PAGE;
		int to = Math.min(available.size(), from + ROWS_PER_PAGE);
		rows = List.copyOf(available.subList(from, to));

		int y = 54;
		for (Row row : rows) {
			int rowY = y;
			if (row.joinRequest()) {
				addRenderableWidget(Button.builder(
						Component.translatable("screen.hardcore_checkpoints.roster.approve"),
						button -> CheckpointClientState.send(ControlAction.APPROVE_JOIN_REQUEST, row.entry().playerId())
				).bounds(width / 2 + 44, rowY, 64, 20).build());
				addRenderableWidget(Button.builder(
						Component.translatable("screen.hardcore_checkpoints.roster.reject"),
						button -> CheckpointClientState.send(ControlAction.REJECT_JOIN_REQUEST, row.entry().playerId())
				).bounds(width / 2 + 112, rowY, 64, 20).build());
			} else {
				addRenderableWidget(Button.builder(
						Component.translatable("screen.hardcore_checkpoints.roster.remove"),
						button -> CheckpointClientState.send(ControlAction.REMOVE_ROSTER_MEMBER, row.entry().playerId())
				).bounds(width / 2 + 112, rowY, 64, 20).build());
			}
			y += 24;
		}

		int footerY = Math.min(height - 52, 54 + ROWS_PER_PAGE * 24 + 8);
		addRenderableWidget(Button.builder(Component.literal("<"), button -> minecraft.setScreen(new CheckpointRosterScreen(actualPage - 1)))
				.bounds(width / 2 - 76, footerY, 24, 20).build()).active = actualPage > 0;
		addRenderableWidget(Button.builder(Component.literal(">"), button -> minecraft.setScreen(new CheckpointRosterScreen(actualPage + 1)))
				.bounds(width / 2 - 48, footerY, 24, 20).build()).active = actualPage < maxPage;
		addRenderableWidget(Button.builder(
				Component.translatable("screen.hardcore_checkpoints.status.refresh"),
				button -> CheckpointClientState.send(ControlAction.REFRESH_STATE, null)
		).bounds(width / 2 - 20, footerY, 84, 20).build());
		addRenderableWidget(Button.builder(
				Component.translatable("gui.back"),
				button -> minecraft.setScreen(new CheckpointStatusScreen())
		).bounds(width / 2 + 68, footerY, 84, 20).build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(graphics, mouseX, mouseY, partialTick);
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(font, title, width / 2, 22, 0xFFFFFF);
		int y = 60;
		for (Row row : rows) {
			graphics.drawString(font, row.entry().displayName(), width / 2 - 176, y, 0xE0E0E0, false);
			graphics.drawString(
					font,
					Component.translatable("screen.hardcore_checkpoints.connection."
							+ row.entry().connectionStatus().toLowerCase(java.util.Locale.ROOT)),
					width / 2 - 48,
					y,
					0xA0A0A0,
					false
			);
			y += 24;
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return !CheckpointClientState.isConfiguring() && !CheckpointClientState.isServerForcedPause();
	}

	@Override
	public boolean isPauseScreen() {
		return CheckpointClientState.isServerForcedPause()
				|| minecraft != null && minecraft.hasSingleplayerServer();
	}

	private record Row(ControlStateSnapshot.RosterEntry entry, boolean joinRequest) {
	}
}
