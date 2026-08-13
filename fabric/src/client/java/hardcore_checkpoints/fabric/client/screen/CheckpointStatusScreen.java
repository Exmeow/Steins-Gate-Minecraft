package hardcore_checkpoints.fabric.client.screen;

import hardcore_checkpoints.fabric.client.state.CheckpointClientState;
import hardcore_checkpoints.server.network.ControlAction;
import hardcore_checkpoints.server.network.ControlStateSnapshot;
import hardcore_checkpoints.server.state.CheckpointPhase;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * 检查点状态与玩家准备操作的客户端界面。
 *
 * <p>按钮集合完全由服务端快照派生；客户端只发送意图，不自行预测状态机结果。</p>
 */
public final class CheckpointStatusScreen extends Screen {
	public CheckpointStatusScreen() {
		super(Component.translatable("screen.hardcore_checkpoints.status.title"));
	}

	@Override
	protected void init() {
		clearWidgets();
		int center = width / 2;
		boolean singleplayer = CheckpointScreenActions.isSingleplayer(minecraft);
		ControlStateSnapshot snapshot = CheckpointClientState.snapshot().orElse(null);

		// 即使服务端快照延迟或损坏，也必须始终保留退出路径。
		addRenderableWidget(Button.builder(
				CheckpointScreenActions.exitLabel(minecraft),
				button -> CheckpointScreenActions.disconnect(minecraft)
		).bounds(center - 100, height - 28, 200, 20).build());
		if (snapshot == null) {
			return;
		}

		// 名单成员操作区：准备状态与返回等候区互斥，死亡倒计时返回前需要二次确认。
		int y = height / 2 + 24;
		if (snapshot.rosterMember()
				&& snapshot.phase() != CheckpointPhase.CREATING_INITIAL_CHECKPOINT) {
			boolean deathCountdown = snapshot.phase() == CheckpointPhase.DEATH_COUNTDOWN;
			if (!deathCountdown) {
				int prepareX = singleplayer ? center - 50 : center - 102;
				addRenderableWidget(Button.builder(
						Component.translatable(snapshot.ready()
								? "screen.hardcore_checkpoints.status.withdraw_ready"
								: "screen.hardcore_checkpoints.status.prepare"),
						button -> CheckpointClientState.send(
								snapshot.ready() ? ControlAction.WITHDRAW_READY : ControlAction.PREPARE,
								null
						)
				).bounds(prepareX, y, 100, 20).build());
			}

			// 返回 Configuration 是多人等候区操作；集成服务器没有独立等候区。
			if (!singleplayer && !CheckpointClientState.isConfiguring()) {
				int returnX = deathCountdown ? center - 50 : center + 2;
				addRenderableWidget(Button.builder(
						Component.translatable("screen.hardcore_checkpoints.status.return_to_waiting"),
						button -> {
							if (deathCountdown) {
								minecraft.setScreen(new ConfirmScreen(confirmed -> {
									if (confirmed) {
										CheckpointClientState.send(ControlAction.RETURN_TO_WAITING, null);
									}
									minecraft.setScreen(new CheckpointStatusScreen());
								}, Component.translatable("screen.hardcore_checkpoints.death_return.title"),
										Component.translatable("screen.hardcore_checkpoints.death_return.warning")));
							} else {
								CheckpointClientState.send(ControlAction.RETURN_TO_WAITING, null);
							}
						}
				).bounds(returnX, y, 100, 20).build());
			}
		} else if (!singleplayer) {
			// 非名单玩家只能提交加入申请，不能进入准备流程。
			boolean requested = "JOIN_REQUESTED".equals(snapshot.statusMessage());
			addRenderableWidget(Button.builder(
					Component.translatable(requested
							? "screen.hardcore_checkpoints.status.withdraw_request"
							: "screen.hardcore_checkpoints.status.request_join"),
					button -> CheckpointClientState.send(
							requested ? ControlAction.WITHDRAW_JOIN_REQUEST : ControlAction.SUBMIT_JOIN_REQUEST,
							null
					)
			).bounds(center - 50, y, 100, 20).build());
		}

		addRenderableWidget(Button.builder(
				Component.translatable("screen.hardcore_checkpoints.status.refresh"),
				button -> CheckpointClientState.send(ControlAction.REFRESH_STATE, null)
		).bounds(center - 50, y + 24, 100, 20).build());
		if (!singleplayer && snapshot.administrator()) {
			addRenderableWidget(Button.builder(
					Component.translatable("screen.hardcore_checkpoints.status.manage_roster"),
					button -> minecraft.setScreen(new CheckpointRosterScreen(0))
			).bounds(center - 50, y + 48, 100, 20).build());
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(graphics, mouseX, mouseY, partialTick);
		super.render(graphics, mouseX, mouseY, partialTick);
		ControlStateSnapshot snapshot = CheckpointClientState.snapshot().orElse(null);
		graphics.drawCenteredString(font, title, width / 2, height / 2 - 70, 0xFFFFFF);
		if (snapshot == null) {
			graphics.drawCenteredString(
					font,
					Component.translatable("screen.hardcore_checkpoints.status.waiting"),
					width / 2,
					height / 2 - 40,
					0xA0A0A0
			);
			return;
		}
		// 倒计时文本使用本地单调时钟推算，避免每帧都依赖新的网络快照。
		Component phaseText = Component.translatable("screen.hardcore_checkpoints.phase."
				+ snapshot.phase().name().toLowerCase(Locale.ROOT));
		if (snapshot.phase() == CheckpointPhase.START_COUNTDOWN) {
			long remainingMillis = CheckpointClientState.countdownMillis();
			phaseText = remainingMillis > 0L
					? Component.translatable(
							"screen.hardcore_checkpoints.phase.start_countdown_value",
							String.format(Locale.ROOT, "%.1f", remainingMillis / 1000.0)
					)
					: Component.translatable("screen.hardcore_checkpoints.phase.start_countdown_stabilizing");
		} else if (snapshot.phase() == CheckpointPhase.DEATH_COUNTDOWN) {
			long remainingMillis = CheckpointClientState.countdownMillis();
			phaseText = Component.translatable(
					"screen.hardcore_checkpoints.phase.death_countdown_value",
					String.format(Locale.ROOT, "%.1f", remainingMillis / 1000.0)
			);
		}
		graphics.drawCenteredString(font, phaseText, width / 2, height / 2 - 44, 0xE0E0E0);
		if (!CheckpointScreenActions.isSingleplayer(minecraft)) {
			graphics.drawCenteredString(
					font,
					Component.translatable("screen.hardcore_checkpoints.status.roster", snapshot.roster().size()),
					width / 2,
					height / 2 - 26,
					0xA0A0A0
			);
		}
	}

	/**
	 * Configuration 阶段或服务端强制暂停期间禁止用 Esc 绕过门禁界面。
	 */
	@Override
	public boolean shouldCloseOnEsc() {
		return !CheckpointClientState.isConfiguring() && !CheckpointClientState.isServerForcedPause();
	}

	@Override
	public boolean isPauseScreen() {
		return CheckpointScreenActions.isSingleplayer(minecraft)
				&& !CheckpointClientState.isServerForcedPause();
	}
}
