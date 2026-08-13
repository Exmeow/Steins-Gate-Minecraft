package hardcore_checkpoints.fabric.recovery;

import hardcore_checkpoints.HardcoreCheckpoints;
import hardcore_checkpoints.checkpoint.SnapshotVerifier;
import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.WorldControlRuntime;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.fabric.network.FabricCheckpointNetworking;
import hardcore_checkpoints.fabric.effect.FabricGlobalEffects;
import hardcore_checkpoints.fabric.platform.FabricGameplayFreezeController;
import hardcore_checkpoints.fabric.supervisor.FabricSupervisorBridge;
import hardcore_checkpoints.recovery.DeathTransaction;
import hardcore_checkpoints.recovery.DeathTransactionRepository;
import hardcore_checkpoints.recovery.DeathTransactionStage;
import hardcore_checkpoints.server.state.CheckpointEvent;
import hardcore_checkpoints.server.state.CheckpointPhase;
import hardcore_checkpoints.server.state.CheckpointServerRuntime;
import hardcore_checkpoints.server.state.CheckpointStateCoordinator;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * 将最终死亡转换为可恢复、可审计的回档事务。
 *
 * <p>首个名单成员死亡时固定二十秒截止时间并立即持久化；后续死亡、断线和心跳变化
 * 均不得延长或缩短该截止时间。只有已持久化的死亡事务能够触发自动回档，普通崩溃不会。</p>
 *
 * <p>专用服务器把停服与恢复交给监督程序；单人游戏则记录待恢复上下文并返回客户端恢复流程。</p>
 */
public final class FabricDeathRecoveryIntegration {
	private static final AtomicFileStore FILE_STORE = new AtomicFileStore();
	private static final DeathTransactionRepository DEATHS = new DeathTransactionRepository();
	private static final SnapshotVerifier VERIFIER = new SnapshotVerifier(FILE_STORE);

	// 运行期倒计时只缓存展示用的单调时钟截止；权威截止时间保存在死亡事务中。
	private static ActiveCountdown activeCountdown;
	private static boolean stopRequested;
	private static long lastDisplayedSecond = Long.MIN_VALUE;
	private static PendingSingleplayerRecovery pendingSingleplayerRecovery;
	private static boolean recoveryCompleted;
	private static final java.util.Set<UUID> RECOVERY_NOTIFIED_PLAYERS = new java.util.HashSet<>();

	private FabricDeathRecoveryIntegration() {
	}

	/**
	 * 注册死亡与服务器生命周期回调。死亡事件先回到服务器线程，再创建事务。
	 */
	public static void initialize() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) {
				UUID playerId = player.getUUID();
				player.getServer().execute(() -> handleFinalDeath(player.getServer(), playerId));
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(FabricDeathRecoveryIntegration::tick);
		ServerLifecycleEvents.SERVER_STARTED.register(FabricDeathRecoveryIntegration::serverStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(FabricDeathRecoveryIntegration::serverActuallyStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
	}

	public static long countdownMillis(long nowNanos) {
		ActiveCountdown countdown = activeCountdown;
		if (countdown == null) {
			return 0L;
		}
		long remaining = countdown.deadlineNanos() - nowNanos;
		return remaining <= 0L ? 0L : (remaining + 999_999L) / 1_000_000L;
	}

	public static PendingSingleplayerRecovery consumePendingSingleplayerRecovery() {
		PendingSingleplayerRecovery pending = pendingSingleplayerRecovery;
		pendingSingleplayerRecovery = null;
		return pending;
	}

	public static void notifyRecoveredPlayer(ServerPlayer player) {
		if (recoveryCompleted && RECOVERY_NOTIFIED_PLAYERS.add(player.getUUID())) {
			player.sendSystemMessage(Component.translatable("message.hardcore_checkpoints.death.recovered"));
		}
	}

	/**
	 * 监督程序完成离线替换后提交 RollbackSucceeded，并在新世界实例中恢复入场门禁。
	 */
	public static void completeSupervisorRecovery(MinecraftServer server) throws IOException {
		CheckpointStateCoordinator coordinator = CheckpointServerRuntime.coordinator().orElse(null);
		if (coordinator == null) {
			return;
		}
		CheckpointPhase phase = coordinator.state().phase();
		if (phase == CheckpointPhase.ROLLBACK_PENDING || phase == CheckpointPhase.RESTORING) {
			var transition = coordinator.submit(new CheckpointEvent.RollbackSucceeded());
			if (!transition.accepted()) {
				throw new IOException("Supervisor recovery completion was rejected: " + transition.code());
			}
			recoveryCompleted = true;
			RECOVERY_NOTIFIED_PLAYERS.clear();
			FabricGlobalEffects.queueRollbackSuccess();
		}
	}

	/**
	 * 首次死亡先验证已发布检查点并持久化死亡事务，再进入倒计时状态。
	 * 任一步失败都故障安全停服，绝不允许在无可靠回档事务时继续游戏。
	 */
	private static void handleFinalDeath(MinecraftServer server, UUID playerId) {
		CheckpointStateCoordinator coordinator = CheckpointServerRuntime.coordinator().orElse(null);
		ControlRootLayout layout = currentLayout();
		if (coordinator == null || layout == null || !coordinator.state().roster().contains(playerId)) {
			return;
		}
		CheckpointPhase phase = coordinator.state().phase();
		if (phase != CheckpointPhase.RUNNING && phase != CheckpointPhase.DEATH_COUNTDOWN) {
			return;
		}

		try {
			if (phase == CheckpointPhase.RUNNING) {
				recoveryCompleted = false;
				RECOVERY_NOTIFIED_PLAYERS.clear();
				var metadata = WorldControlRuntime.current()
						.flatMap(result -> result.optionalMetadata())
						.orElseThrow(() -> new IOException("Active world control metadata is unavailable"));
				var pointer = VERIFIER.verifyPublished(layout, metadata.identity());
				Instant now = Instant.now();
				DeathTransaction transaction = DEATHS.begin(layout, pointer, playerId, now);
				long deadlineNanos = System.nanoTime() + DeathTransaction.COUNTDOWN_DURATION.toNanos();
				activeCountdown = new ActiveCountdown(transaction.transactionId(), deadlineNanos);
				lastDisplayedSecond = Long.MIN_VALUE;

				var transition = coordinator.submit(new CheckpointEvent.DeathCountdownStarted());
				if (!transition.accepted()) {
					throw new IOException("Death countdown state transition was rejected: " + transition.code());
				}
				ServerPlayer deadPlayer = server.getPlayerList().getPlayer(playerId);
				Component deadPlayerName = deadPlayer == null
						? Component.literal(playerId.toString())
						: deadPlayer.getDisplayName();
				Component notice = server.isSingleplayer()
						? Component.translatable("message.hardcore_checkpoints.death.first.singleplayer")
						: Component.translatable(
								"message.hardcore_checkpoints.death.first.multiplayer",
								deadPlayerName
						);
				server.getPlayerList().broadcastSystemMessage(notice, false);
				FabricCheckpointNetworking.broadcastCurrentState(server, coordinator);
				HardcoreCheckpoints.LOGGER.warn(
						"Death rollback transaction {} started by {} for checkpoint {}; fixed deadline is {}",
						transaction.transactionId(),
						playerId,
						transaction.checkpointId(),
						transaction.countdownDeadlineUtc()
				);
			} else {
				ensureRuntimeCountdown(layout);
			}
			forceVanillaSpectator(server, playerId);
		} catch (IOException | RuntimeException exception) {
			HardcoreCheckpoints.LOGGER.error(
					"Failed to commit the death recovery transaction for {}; stopping to prevent continued play",
					playerId,
					exception
			);
			stopRequested = true;
			server.execute(() -> server.halt(false));
		}
	}

	/**
	 * 依据持久化的固定截止时间推进倒计时。到期后先提交 ROLLBACK_PENDING，再请求受控停服。
	 */
	private static void tick(MinecraftServer server) {
		CheckpointStateCoordinator coordinator = CheckpointServerRuntime.coordinator().orElse(null);
		if (coordinator == null || coordinator.state().phase() != CheckpointPhase.DEATH_COUNTDOWN) {
			return;
		}
		ControlRootLayout layout = currentLayout();
		if (layout == null) {
			return;
		}
		try {
			ensureRuntimeCountdown(layout);
			long remainingMillis = countdownMillis(System.nanoTime());
			long displayedSecond = (remainingMillis + 999L) / 1000L;
			if (displayedSecond != lastDisplayedSecond) {
				lastDisplayedSecond = displayedSecond;
				displayCountdown(server, coordinator, displayedSecond);
			}
			if (remainingMillis == 0L && !stopRequested) {
				DeathTransaction transaction = commitRollbackTransaction(layout, Instant.now());
				stopRequested = true;
				FabricSupervisorBridge.requestRollbackAndStop(
						server,
						transaction,
						() -> {
							try {
								transitionRollbackPending(server, coordinator, layout, transaction);
							} catch (IOException exception) {
								HardcoreCheckpoints.LOGGER.error(
										"Failed to publish rollback-pending state before shutdown",
										exception
								);
							}
							server.getPlayerList().broadcastSystemMessage(
									Component.translatable("message.hardcore_checkpoints.death.rollback_starting"),
									false
							);
							FabricCheckpointNetworking.broadcastCurrentState(server, coordinator);
						}
				);
			}
		} catch (IOException | RuntimeException exception) {
			HardcoreCheckpoints.LOGGER.error("Failed to advance the death rollback countdown", exception);
			if (!stopRequested) {
				stopRequested = true;
				server.execute(() -> server.halt(false));
			}
		}
	}

	/**
	 * 启动时恢复未完成死亡事务。专服等待监督程序确认；单人模式在验证快照身份后完成健康提交。
	 */
	private static void serverStarted(MinecraftServer server) {
		CheckpointStateCoordinator coordinator = CheckpointServerRuntime.coordinator().orElse(null);
		ControlRootLayout layout = currentLayout();
		if (coordinator == null || layout == null) {
			return;
		}
		try {
			DeathTransaction active = DEATHS.findActive(layout).orElse(null);
			if (active == null) {
				return;
			}
			if (active.stage() != DeathTransactionStage.RESTORING) {
				HardcoreCheckpoints.LOGGER.error(
						"Refusing to run a loaded world with active death transaction {} in stage {}",
						active.transactionId(),
						active.stage()
				);
				server.execute(() -> server.halt(false));
				return;
			}
			if (!server.isSingleplayer()) {
				FabricGameplayFreezeController.ensureFrozen(server);
				return;
			}
			var metadata = WorldControlRuntime.current()
					.flatMap(result -> result.optionalMetadata())
					.orElseThrow(() -> new IOException("Recovered world metadata is unavailable"));
			var pointer = VERIFIER.verifyPublished(layout, metadata.identity());
			if (!pointer.latestCheckpointId().equals(active.checkpointId())) {
				throw new IOException("Recovered server started against a different checkpoint");
			}
			if (coordinator.state().phase() != CheckpointPhase.ROLLBACK_PENDING
					&& coordinator.state().phase() != CheckpointPhase.RESTORING) {
				throw new IOException("Recovered server control state is not awaiting rollback completion: "
						+ coordinator.state().phase());
			}
			FabricGameplayFreezeController.ensureFrozen(server);
			new hardcore_checkpoints.recovery.OfflineRollbackService(path -> true).confirmHealthy(
					layout,
					metadata.identity(),
					active.checkpointId(),
					Instant.now()
			);
			var transition = coordinator.submit(new CheckpointEvent.RollbackSucceeded());
			if (!transition.accepted()) {
				throw new IOException("Recovered server state transition was rejected: " + transition.code());
			}
			recoveryCompleted = true;
			FabricGlobalEffects.queueRollbackSuccess();
			RECOVERY_NOTIFIED_PLAYERS.clear();
			FabricCheckpointNetworking.broadcastCurrentState(server, coordinator);
			HardcoreCheckpoints.LOGGER.warn(
					"Death rollback transaction {} passed startup health validation for checkpoint {}",
					active.transactionId(),
					active.checkpointId()
			);
		} catch (IOException | RuntimeException exception) {
			HardcoreCheckpoints.LOGGER.error("Recovered world failed startup health validation", exception);
			try {
				DeathTransaction failed = DEATHS.findActive(layout).orElse(null);
				if (failed != null && failed.stage() == DeathTransactionStage.RESTORING) {
					String message = exception.getMessage() == null
							? exception.getClass().getSimpleName()
							: exception.getMessage();
					DEATHS.save(layout, failed.fail("STARTUP_HEALTH_FAILED", message, Instant.now()));
				}
				if (coordinator.state().phase() == CheckpointPhase.ROLLBACK_PENDING
						|| coordinator.state().phase() == CheckpointPhase.RESTORING) {
					coordinator.submit(new CheckpointEvent.RollbackFailed());
					FabricCheckpointNetworking.broadcastCurrentState(server, coordinator);
				}
			} catch (IOException | RuntimeException failureToPersist) {
				HardcoreCheckpoints.LOGGER.error("Failed to persist RECOVERY_FAILED after startup validation", failureToPersist);
			}
			server.execute(() -> server.halt(false));
		}
	}

	/**
	 * 即使服务器在倒计时期间被外部关闭，也要把已存在的死亡事务推进到待回档阶段。
	 */
	private static void serverActuallyStopping(MinecraftServer server) {
		CheckpointStateCoordinator coordinator = CheckpointServerRuntime.coordinator().orElse(null);
		ControlRootLayout layout = currentLayout();
		if (coordinator == null || layout == null) {
			return;
		}
		try {
			var active = DEATHS.findActive(layout).orElse(null);
			if (active != null && (active.stage() == DeathTransactionStage.COUNTDOWN
					|| active.stage() == DeathTransactionStage.ROLLBACK_PENDING)) {
				DeathTransaction transaction = commitRollbackTransaction(layout, Instant.now());
				transitionRollbackPending(server, coordinator, layout, transaction);
				FabricCheckpointNetworking.broadcastCurrentState(server, coordinator);
			}
		} catch (IOException | RuntimeException exception) {
			HardcoreCheckpoints.LOGGER.error("Failed to commit death rollback while the server was stopping", exception);
		}
	}

	/**
	 * 幂等提交回档事务；已处于 ROLLBACK_PENDING 时保持原事务，不创建替代事务。
	 */
	private static DeathTransaction commitRollbackTransaction(
			ControlRootLayout layout,
			Instant now
	) throws IOException {
		DeathTransaction transaction = DEATHS.requireActive(layout);
		if (transaction.stage() == DeathTransactionStage.COUNTDOWN) {
			transaction = transaction.transitionTo(DeathTransactionStage.ROLLBACK_PENDING, now);
			DEATHS.save(layout, transaction);
		}
		return transaction;
	}

	private static void transitionRollbackPending(
			MinecraftServer server,
			CheckpointStateCoordinator coordinator,
			ControlRootLayout layout,
			DeathTransaction transaction
	) throws IOException {
		if (coordinator.state().phase() == CheckpointPhase.DEATH_COUNTDOWN) {
			var transition = coordinator.submit(new CheckpointEvent.ServerActuallyStopping());
			if (!transition.accepted()) {
				throw new IOException("Rollback-pending state transition was rejected: " + transition.code());
			}
		}
		if (server.isSingleplayer()) {
			pendingSingleplayerRecovery = new PendingSingleplayerRecovery(
					layout,
					layout.worldRoot().getFileName().toString(),
					transaction.transactionId()
			);
		}
		HardcoreCheckpoints.LOGGER.warn(
				"Death rollback transaction {} committed ROLLBACK_PENDING; requesting server stop",
				transaction.transactionId()
		);
	}

	/**
	 * 从 UTC 权威截止时间重建本进程单调时钟倒计时，避免系统时间在运行期跳变。
	 */
	private static void ensureRuntimeCountdown(ControlRootLayout layout) throws IOException {
		if (activeCountdown != null) {
			return;
		}
		DeathTransaction transaction = DEATHS.requireActive(layout);
		if (transaction.stage() != DeathTransactionStage.COUNTDOWN) {
			throw new IOException("Cannot resume an in-process countdown from " + transaction.stage());
		}
		long remainingMillis = Math.max(0L, transaction.countdownDeadline().toEpochMilli() - Instant.now().toEpochMilli());
		activeCountdown = new ActiveCountdown(
				transaction.transactionId(),
				System.nanoTime() + remainingMillis * 1_000_000L
		);
	}

	private static void displayCountdown(
			MinecraftServer server,
			CheckpointStateCoordinator coordinator,
			long seconds
	) {
		Component message = Component.translatable("message.hardcore_checkpoints.death.countdown", seconds);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (coordinator.state().roster().contains(player.getUUID())) {
				player.displayClientMessage(message, true);
			}
		}
	}

	private static void forceVanillaSpectator(MinecraftServer server, UUID playerId) {
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		if (player == null || player.isSpectator()) {
			return;
		}
		if (player.getHealth() <= 0.0F) {
			var connection = player.connection;
			player = server.getPlayerList().respawn(player, false, RemovalReason.KILLED);
			connection.player = player;
		}
		player.setGameMode(GameType.SPECTATOR);
	}

	private static ControlRootLayout currentLayout() {
		return WorldControlRuntime.current()
				.flatMap(result -> result.optionalLayout())
				.orElse(null);
	}

	private static void clear() {
		activeCountdown = null;
		stopRequested = false;
		lastDisplayedSecond = Long.MIN_VALUE;
	}

	public record PendingSingleplayerRecovery(
			ControlRootLayout layout,
			String levelId,
			UUID transactionId
	) {
	}

	private record ActiveCountdown(UUID transactionId, long deadlineNanos) {
	}
}
