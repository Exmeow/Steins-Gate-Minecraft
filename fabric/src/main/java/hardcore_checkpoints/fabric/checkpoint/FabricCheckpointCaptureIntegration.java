package hardcore_checkpoints.fabric.checkpoint;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import hardcore_checkpoints.HardcoreCheckpoints;
import hardcore_checkpoints.block.CheckpointCoreBlock;
import hardcore_checkpoints.checkpoint.CheckpointCaptureService;
import hardcore_checkpoints.checkpoint.CheckpointCompensation;
import hardcore_checkpoints.checkpoint.CheckpointCompensationRepository;
import hardcore_checkpoints.checkpoint.CheckpointPointer;
import hardcore_checkpoints.checkpoint.RecorderSelectionService;
import hardcore_checkpoints.checkpoint.SnapshotVerifier;
import hardcore_checkpoints.control.ControlMetadata;
import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.WorldControlRuntime;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.fabric.network.FabricCheckpointNetworking;
import hardcore_checkpoints.fabric.platform.FabricGameplayFreezeController;
import hardcore_checkpoints.fabric.effect.FabricGlobalEffects;
import hardcore_checkpoints.registry.ModBlocks;
import hardcore_checkpoints.server.state.CheckpointEvent;
import hardcore_checkpoints.server.state.CheckpointPhase;
import hardcore_checkpoints.server.state.CheckpointServerRuntime;
import hardcore_checkpoints.server.state.CheckpointStateCoordinator;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 将 Minecraft 世界保存流程接入离线可验证的检查点快照事务。
 *
 * <p>服务器线程负责冻结、刷盘和状态转换；耗时目录复制在单线程执行器中完成。
 * 复制结果必须回到服务器线程，经清单验证和原子发布后才能推进状态机。</p>
 *
 * <p>补偿日志覆盖“目录已发布但状态尚未提交”的崩溃窗口，启动时必须先恢复补偿再创建新快照。</p>
 */
public final class FabricCheckpointCaptureIntegration {
	private static final long CONDITION_STABILITY_NANOS = Duration.ofSeconds(5).toNanos();
	private static final DustParticleOptions RECORDER_PARTICLE = new DustParticleOptions(
			new Vector3f(0.0F, 1.0F, 1.0F),
			1.0F
	);
	private static final double RECORDER_RANGE = 8.0;
	private static final double POSITION_TOLERANCE_SQUARED = 1.0 / (32.0 * 32.0);
	private static final AtomicFileStore FILE_STORE = new AtomicFileStore();
	private static final CheckpointCaptureService CAPTURE_SERVICE = new CheckpointCaptureService(FILE_STORE);
	private static final CheckpointCompensationRepository COMPENSATION_REPOSITORY =
			new CheckpointCompensationRepository(FILE_STORE);
	private static final SnapshotVerifier SNAPSHOT_VERIFIER = new SnapshotVerifier(FILE_STORE);
	private static final RecorderSelectionService SELECTION_SERVICE = new RecorderSelectionService();
	private static final ConditionMonitor CONDITION_MONITOR = new ConditionMonitor();
	// 所有复制串行执行，避免两个快照同时读取和发布同一世界目录。
	private static final ExecutorService COPY_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "hardcore-checkpoints-snapshot-copy");
		thread.setDaemon(true);
		return thread;
	});
	// 完成回调会投递到服务器队列；停服时任务可能被内联执行，因此分发处仍需复核线程。
	private static final AtomicLong LIFECYCLE_GENERATION = new AtomicLong();

	private static volatile boolean captureActive;
	private static volatile UUID activeCheckpointId;
	private static volatile long activeCaptureStartedNanos;
	private static UUID recoveredWorldInstanceId;

	private FabricCheckpointCaptureIntegration() {
	}

	public static void initialize() {
		ServerTickEvents.END_SERVER_TICK.register(FabricCheckpointCaptureIntegration::tick);
	}

	/**
	 * 递增生命周期代次，使上一个服务器实例尚未返回的异步完成回调自动失效。
	 */
	public static void clear() {
		long generation = LIFECYCLE_GENERATION.incrementAndGet();
		clearActiveCapture();
		recoveredWorldInstanceId = null;
		CONDITION_MONITOR.reset(null);
		HardcoreCheckpoints.LOGGER.debug("Cleared checkpoint capture lifecycle (generation={})", generation);
	}

	/**
	 * 每 tick 只在 RUNNING 阶段评估自动保存条件；正在保存或恢复时不重复触发复制。
	 */
	private static void tick(MinecraftServer server) {
		CheckpointStateCoordinator coordinator = CheckpointServerRuntime.coordinator().orElse(null);
		WorldContext context = currentContext();
		if (coordinator == null || context == null || !coordinator.state().featureEnabled()) {
			CONDITION_MONITOR.reset(server);
			return;
		}
		try {
			recoverCompensationIfNeeded(server, context, coordinator);
			if (coordinator.state().phase() == CheckpointPhase.RUNNING) {
				CONDITION_MONITOR.evaluate(server, coordinator);
			} else if (coordinator.state().phase() != CheckpointPhase.QUIESCING) {
				CONDITION_MONITOR.reset(server);
			}
			if (!captureActive && coordinator.state().phase() == CheckpointPhase.CREATING_INITIAL_CHECKPOINT) {
				startCapture(server, coordinator, context, Set.of(), true);
			} else if (!captureActive && coordinator.state().phase() == CheckpointPhase.QUIESCING) {
				startCapture(server, coordinator, context, CONDITION_MONITOR.consumeSelection(), false);
			}
		} catch (IOException | RuntimeException exception) {
			HardcoreCheckpoints.LOGGER.error("Checkpoint capture tick failed", exception);
		}
	}

	/**
	 * 把复制线程的结果安全交回服务器线程。代次校验阻止旧服务器实例的回调污染新世界。
	 */
	private static void dispatchCompletion(
			MinecraftServer server,
			UUID checkpointId,
			CaptureCompletion completion
	) {
		try {
			server.execute(() -> {
				if (!server.isSameThread()) {
					HardcoreCheckpoints.LOGGER.info(
							"Checkpoint copy {} completed during server shutdown; restart recovery will inspect the publication",
							checkpointId
					);
					return;
				}
				if (completion.generation() != LIFECYCLE_GENERATION.get()) {
					HardcoreCheckpoints.LOGGER.debug(
							"Discarded checkpoint completion from stale lifecycle generation {}",
							completion.generation()
					);
					return;
				}
				try {
					completion.action().run();
				} catch (RuntimeException exception) {
					HardcoreCheckpoints.LOGGER.error("Checkpoint completion failed on the server thread", exception);
				}
			});
		} catch (RuntimeException exception) {
			HardcoreCheckpoints.LOGGER.info(
					"Checkpoint copy {} could not schedule completion during shutdown; restart recovery will inspect the publication",
					checkpointId
			);
		}
	}

	private static void scheduleCaptureHeartbeat(UUID checkpointId, long generation) {
		CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS).execute(() -> {
			if (!captureActive
					|| generation != LIFECYCLE_GENERATION.get()
					|| !checkpointId.equals(activeCheckpointId)) {
				return;
			}
			HardcoreCheckpoints.LOGGER.info(
					"Checkpoint capture {} is still running (elapsedSeconds={})",
					checkpointId,
					Duration.ofNanos(System.nanoTime() - activeCaptureStartedNanos).toSeconds()
			);
			scheduleCaptureHeartbeat(checkpointId, generation);
		});
	}

	private static void clearActiveCapture() {
		captureActive = false;
		activeCheckpointId = null;
		activeCaptureStartedNanos = 0;
	}

	/**
	 * 快照前置事务顺序：预检空间、保存补偿、消耗记录器、进入 SAVING、刷盘，最后启动异步复制。
	 * 顺序不可交换，否则崩溃恢复无法判断资源与快照哪一侧已经提交。
	 */
	private static void startCapture(
			MinecraftServer server,
			CheckpointStateCoordinator coordinator,
			WorldContext context,
			Set<RecorderLocation> selected,
			boolean initial
	) {
		captureActive = true;
		UUID checkpointId = UUID.randomUUID();
		long generation = LIFECYCLE_GENERATION.get();
		long startedNanos = System.nanoTime();
		activeCheckpointId = checkpointId;
		activeCaptureStartedNanos = startedNanos;
		scheduleCaptureHeartbeat(checkpointId, generation);
		CheckpointCompensation compensation = null;
		try {
			HardcoreCheckpoints.LOGGER.info(
					"Running checkpoint preflight {} (initial={}, generation={})",
					checkpointId,
					initial,
					generation
			);
			CheckpointCaptureService.CapturePlan plan = CAPTURE_SERVICE.preflight(context.layout());
			HardcoreCheckpoints.LOGGER.info(
					"Starting checkpoint capture {} (initial={}, worldBytes={}, requiredBytes={}, generation={})",
					checkpointId,
					initial,
					plan.worldBytes(),
					plan.requiredBytes(),
					generation
			);
			if (!initial) {
				if (selected.isEmpty()) {
					throw new IOException("Checkpoint transaction has no selected recorder cores");
				}
				compensation = CheckpointCompensation.create(
						context.metadata().identity(),
						checkpointId,
						captureRecorderStates(server, selected)
				);
				COMPENSATION_REPOSITORY.save(context.layout(), compensation);
				consumeRecorders(server, selected);
				var saving = coordinator.submit(new CheckpointEvent.SavingStarted());
				if (!saving.accepted()) {
					throw new IOException("Checkpoint state rejected SAVING transition: " + saving.code());
				}
			}
			if (!server.saveEverything(true, true, true)) {
				throw new IOException("Minecraft saveEverything flush returned false");
			}
			CheckpointCompensation finalCompensation = compensation;
			CompletableFuture
					.supplyAsync(() -> captureUnchecked(context, checkpointId), COPY_EXECUTOR)
					.whenComplete((result, failure) -> dispatchCompletion(
							server,
							checkpointId,
							new CaptureCompletion(
									generation,
									() -> finishCapture(
											server,
											coordinator,
											context,
											selected,
											finalCompensation,
											result,
											failure,
											checkpointId,
											initial,
											startedNanos
									)
							)
					));
		} catch (IOException | RuntimeException exception) {
			clearActiveCapture();
			failBeforeCopy(server, coordinator, context, selected, compensation, initial, exception);
		}
	}

	/**
	 * 复制线程只接触文件系统和不可变上下文，不得调用 Minecraft 服务器对象。
	 */
	private static CaptureOutcome captureUnchecked(WorldContext context, UUID checkpointId) {
		HardcoreCheckpoints.LOGGER.info("Checkpoint copy worker started for {}", checkpointId);
		try {
			CheckpointCaptureService.CaptureResult result = CAPTURE_SERVICE.capture(
					context.layout(),
					context.metadata().identity(),
					checkpointId
			);
			HardcoreCheckpoints.LOGGER.info(
					"Checkpoint copy worker finished for {} (bytes={}, files={})",
					checkpointId,
					result.manifest().totalBytes(),
					result.manifest().files().size()
			);
			return new CaptureOutcome(result, null);
		} catch (IOException | RuntimeException exception) {
			return new CaptureOutcome(null, exception);
		}
	}

	/**
	 * 在服务器线程提交复制结果。只有快照原子发布、补偿清除和状态转换全部成功后才允许解冻。
	 */
	private static void finishCapture(
			MinecraftServer server,
			CheckpointStateCoordinator coordinator,
			WorldContext context,
			Set<RecorderLocation> selected,
			CheckpointCompensation compensation,
			CaptureOutcome outcome,
			Throwable completionFailure,
			UUID checkpointId,
			boolean initial,
			long startedNanos
	) {
		clearActiveCapture();
		Throwable failure = completionFailure != null
				? completionFailure
				: outcome == null ? new IOException("Checkpoint copy produced no result") : outcome.failure();
		try {
			if (failure != null || outcome == null || outcome.result() == null) {
				throw new IOException("Checkpoint snapshot copy failed", failure);
			}
			if (compensation != null) {
				COMPENSATION_REPOSITORY.clear(context.layout());
			}
			var transition = coordinator.submit(new CheckpointEvent.CheckpointSucceeded(
					System.nanoTime(),
					initial && server.isSingleplayer()
			));
			if (!transition.accepted()) {
				throw new IOException("Checkpoint state rejected successful publication: " + transition.code());
			}
			if (coordinator.state().phase() == CheckpointPhase.RUNNING) {
				FabricGameplayFreezeController.release(server);
			}
			FabricCheckpointNetworking.broadcastCurrentState(server, coordinator);
			FabricGlobalEffects.queueCheckpointSuccess();
			long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
			HardcoreCheckpoints.LOGGER.info(
					"Checkpoint capture {} published (initial={}, bytes={}, files={}, elapsedMs={}, phase={})",
					checkpointId,
					initial,
					outcome.result().manifest().totalBytes(),
					outcome.result().manifest().files().size(),
					elapsedMillis,
					coordinator.state().phase()
			);
			notifyRoster(server, coordinator, "message.hardcore_checkpoints.checkpoint.created");
		} catch (IOException | RuntimeException exception) {
			handleCaptureFailure(server, coordinator, context, selected, compensation, exception);
		}
	}

	private static void failBeforeCopy(
			MinecraftServer server,
			CheckpointStateCoordinator coordinator,
			WorldContext context,
			Set<RecorderLocation> selected,
			CheckpointCompensation compensation,
			boolean initial,
			Throwable failure
	) {
		try {
			if (!initial && coordinator.state().phase() == CheckpointPhase.QUIESCING) {
				coordinator.submit(new CheckpointEvent.SavingStarted());
			}
			if (compensation == null) {
				extinguishRecorders(server, selected);
			}
		} catch (IOException transitionFailure) {
			failure.addSuppressed(transitionFailure);
		}
		handleCaptureFailure(server, coordinator, context, selected, compensation, failure);
	}

	/**
	 * 失败路径优先恢复已消耗记录器，再提交 CheckpointFailed；补偿恢复失败时保留错误以供启动恢复。
	 */
	private static void handleCaptureFailure(
			MinecraftServer server,
			CheckpointStateCoordinator coordinator,
			WorldContext context,
			Set<RecorderLocation> selected,
			CheckpointCompensation compensation,
			Throwable failure
	) {
		HardcoreCheckpoints.LOGGER.error("Checkpoint creation failed", failure);
		try {
			if (compensation != null) {
				restoreCompensation(server, compensation);
				COMPENSATION_REPOSITORY.clear(context.layout());
			} else {
				extinguishRecorders(server, selected);
			}
			coordinator.submit(new CheckpointEvent.CheckpointFailed());
		} catch (IOException | RuntimeException restoreFailure) {
			failure.addSuppressed(restoreFailure);
			HardcoreCheckpoints.LOGGER.error("Checkpoint compensation failed; recovery is required before running", restoreFailure);
		}
		notifyRoster(server, coordinator, "message.hardcore_checkpoints.checkpoint.failed");
	}

	/**
	 * 启动后只处理一次当前 worldInstanceId 的补偿。已发布则补交成功事件，未发布则恢复记录器。
	 */
	private static void recoverCompensationIfNeeded(
			MinecraftServer server,
			WorldContext context,
			CheckpointStateCoordinator coordinator
	) throws IOException {
		UUID instanceId = context.metadata().identity().worldInstanceId();
		if (instanceId.equals(recoveredWorldInstanceId)) {
			return;
		}

		CheckpointPointer publishedPointer = null;
		try {
			publishedPointer = SNAPSHOT_VERIFIER.verifyPublished(
					context.layout(),
					context.metadata().identity()
			);
		} catch (IOException ignored) {
			// 下方会根据补偿日志区分“从未发布”和“已提交但状态回调丢失”两种情况。
		}

		Optional<CheckpointCompensation> pending = COMPENSATION_REPOSITORY.load(context.layout());
		if (pending.isPresent()) {
			CheckpointCompensation compensation = pending.orElseThrow();
			boolean published = publishedPointer != null
					&& publishedPointer.latestCheckpointId().equals(compensation.transactionId());
			if (published) {
				COMPENSATION_REPOSITORY.clear(context.layout());
				if (coordinator.state().phase() == CheckpointPhase.SAVING) {
					coordinator.submit(new CheckpointEvent.CheckpointSucceeded(System.nanoTime(), false));
				}
				HardcoreCheckpoints.LOGGER.info(
						"Recovered committed checkpoint transaction {} after restart",
						compensation.transactionId()
				);
			} else {
				restoreCompensation(server, compensation);
				COMPENSATION_REPOSITORY.clear(context.layout());
				if (coordinator.state().phase() == CheckpointPhase.SAVING) {
					coordinator.submit(new CheckpointEvent.CheckpointFailed());
				}
				HardcoreCheckpoints.LOGGER.warn(
						"Restored recorder compensation for uncommitted checkpoint transaction {}",
						compensation.transactionId()
				);
			}
		} else if (publishedPointer != null
				&& coordinator.state().phase() == CheckpointPhase.CREATING_INITIAL_CHECKPOINT) {
			// 快照可能在停服前完成发布，而服务器线程完成回调会按生命周期设计被丢弃。
			coordinator.submit(new CheckpointEvent.CheckpointSucceeded(
					System.nanoTime(),
					server.isSingleplayer()
			));
			HardcoreCheckpoints.LOGGER.info(
					"Recovered published initial checkpoint {} after restart",
					publishedPointer.latestCheckpointId()
			);
		}
		recoveredWorldInstanceId = instanceId;
	}

	/** 在修改记录器方块前保存完整方块状态与方块实体 NBT，作为事务补偿数据。 */
	private static List<CheckpointCompensation.RecorderState> captureRecorderStates(
			MinecraftServer server,
			Collection<RecorderLocation> selected
	) throws IOException {
		List<CheckpointCompensation.RecorderState> states = new ArrayList<>();
		for (RecorderLocation location : selected) {
			ServerLevel level = server.getLevel(location.dimension());
			if (level == null) {
				throw new IOException("Recorder dimension is not loaded: " + location.dimension().location());
			}
			BlockState state = level.getBlockState(location.pos());
			if (!state.is(ModBlocks.checkpointCore().value())) {
				throw new IOException("Selected recorder no longer exists at " + location.pos());
			}
			BlockEntity blockEntity = level.getBlockEntity(location.pos());
			CompoundTag blockEntityTag = blockEntity == null
					? new CompoundTag()
					: blockEntity.saveWithFullMetadata(level.registryAccess());
			states.add(new CheckpointCompensation.RecorderState(
					location.dimension().location().toString(),
					location.pos().getX(),
					location.pos().getY(),
					location.pos().getZ(),
					NbtUtils.writeBlockState(state).toString(),
					blockEntityTag.toString()
			));
		}
		return List.copyOf(states);
	}

	private static void consumeRecorders(MinecraftServer server, Collection<RecorderLocation> selected) throws IOException {
		for (RecorderLocation location : selected) {
			ServerLevel level = server.getLevel(location.dimension());
			if (level == null || !level.setBlock(location.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)) {
				throw new IOException("Failed to consume recorder at " + location.pos());
			}
		}
	}

	/** 按补偿日志重建记录器；任一维度未加载都视为不可安全恢复。 */
	private static void restoreCompensation(MinecraftServer server, CheckpointCompensation compensation) throws IOException {
		for (CheckpointCompensation.RecorderState recorder : compensation.recorders()) {
			ResourceKey<Level> dimension = ResourceKey.create(
					Registries.DIMENSION,
					ResourceLocation.parse(recorder.dimension())
			);
			ServerLevel level = server.getLevel(dimension);
			if (level == null) {
				throw new IOException("Cannot restore recorder in unloaded dimension " + recorder.dimension());
			}
			BlockPos pos = new BlockPos(recorder.x(), recorder.y(), recorder.z());
			BlockState state = NbtUtils.readBlockState(
					level.holderLookup(Registries.BLOCK),
					parseTag(recorder.blockStateSnbt())
			);
			if (state.hasProperty(CheckpointCoreBlock.LIT)) {
				state = state.setValue(CheckpointCoreBlock.LIT, false);
			}
			level.setBlock(pos, state, Block.UPDATE_ALL);
			BlockEntity blockEntity = level.getBlockEntity(pos);
			if (blockEntity != null && !recorder.blockEntitySnbt().equals("{}")) {
				blockEntity.loadWithComponents(parseTag(recorder.blockEntitySnbt()), level.registryAccess());
				blockEntity.setChanged();
			}
		}
	}

	private static CompoundTag parseTag(String snbt) throws IOException {
		try {
			return TagParser.parseTag(snbt);
		} catch (CommandSyntaxException exception) {
			throw new IOException("Invalid recorder compensation SNBT", exception);
		}
	}

	private static void extinguishRecorders(MinecraftServer server, Collection<RecorderLocation> selected) {
		for (RecorderLocation location : selected) {
			ServerLevel level = server.getLevel(location.dimension());
			if (level == null) {
				continue;
			}
			BlockState state = level.getBlockState(location.pos());
			if (state.is(ModBlocks.checkpointCore().value())
					&& state.hasProperty(CheckpointCoreBlock.LIT)
					&& state.getValue(CheckpointCoreBlock.LIT)) {
				level.setBlock(location.pos(), state.setValue(CheckpointCoreBlock.LIT, false), Block.UPDATE_ALL);
			}
		}
	}

	private static WorldContext currentContext() {
		return WorldControlRuntime.current().flatMap(result -> {
			if (result.optionalLayout().isEmpty() || result.optionalMetadata().isEmpty()) {
				return Optional.empty();
			}
			return Optional.of(new WorldContext(
					result.optionalLayout().orElseThrow(),
					result.optionalMetadata().orElseThrow()
			));
		}).orElse(null);
	}

	private static void notifyRoster(MinecraftServer server, CheckpointStateCoordinator coordinator, String translationKey) {
		for (UUID playerId : coordinator.state().roster().members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null) {
				player.displayClientMessage(Component.translatable(translationKey), false);
			}
		}
	}

	private static final class ConditionMonitor {
		private final Map<UUID, Vec3> baselinePositions = new HashMap<>();
		private Set<RecorderLocation> stableSelection = Set.of();
		private Set<RecorderLocation> pendingSelection = Set.of();
		private long stableSinceNanos;

		void evaluate(MinecraftServer server, CheckpointStateCoordinator coordinator) throws IOException {
			ConditionResult result = evaluateConditions(server, coordinator);
			if (!result.satisfied()) {
				reset(server);
				return;
			}
			long now = System.nanoTime();
			boolean moved = movedBeyondTolerance(result.players());
			boolean selectionChanged = !stableSelection.equals(result.selection());
			if (stableSinceNanos == 0 || moved || selectionChanged) {
				stableSinceNanos = now;
				baselinePositions.clear();
				baselinePositions.putAll(result.players());
				stableSelection = result.selection();
			}
			long elapsed = now - stableSinceNanos;
			long remainingMillis = Math.max(0, Duration.ofNanos(CONDITION_STABILITY_NANOS - elapsed).toMillis());
			spawnSelectedRecorderParticles(server, stableSelection, elapsed);
			int displaySeconds = (int) Math.max(1, (remainingMillis + 999) / 1000);
			for (UUID playerId : coordinator.state().roster().members()) {
				ServerPlayer player = server.getPlayerList().getPlayer(playerId);
				if (player != null) {
					player.displayClientMessage(Component.translatable(
							"message.hardcore_checkpoints.checkpoint.countdown",
							displaySeconds
					), true);
				}
			}
			if (elapsed >= CONDITION_STABILITY_NANOS) {
				var transition = coordinator.submit(new CheckpointEvent.CheckpointRequested());
				if (transition.accepted()) {
					pendingSelection = stableSelection;
					reset(server);
				}
			}
		}

		Set<RecorderLocation> consumeSelection() {
			Set<RecorderLocation> selection = pendingSelection;
			pendingSelection = Set.of();
			return selection;
		}

		void reset(MinecraftServer server) {
			baselinePositions.clear();
			stableSelection = Set.of();
			stableSinceNanos = 0;
			if (server != null) {
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					player.displayClientMessage(Component.empty(), true);
				}
			}
		}

		private boolean movedBeyondTolerance(Map<UUID, Vec3> current) {
			if (!baselinePositions.keySet().equals(current.keySet())) {
				return true;
			}
			for (Map.Entry<UUID, Vec3> entry : current.entrySet()) {
				Vec3 baseline = baselinePositions.get(entry.getKey());
				if (baseline == null || baseline.distanceToSqr(entry.getValue()) > POSITION_TOLERANCE_SQUARED) {
					return true;
				}
			}
			return false;
		}
	}

	private static void spawnSelectedRecorderParticles(
			MinecraftServer server,
			Set<RecorderLocation> selected,
			long elapsedNanos
	) {
		double progress = Math.min(1.0, Math.max(0.0, (double) elapsedNanos / CONDITION_STABILITY_NANOS));
		int intervalTicks = Math.max(1, 4 - (int) Math.floor(progress * 3.0));
		if (server.getTickCount() % intervalTicks != 0) {
			return;
		}
		int count = 1 + (int) Math.floor(progress * 4.0);
		double speed = 0.04 + progress * progress * 0.18;
		for (RecorderLocation location : selected) {
			ServerLevel level = server.getLevel(location.dimension());
			if (level == null) {
				continue;
			}
			BlockState state = level.getBlockState(location.pos());
			if (!state.is(ModBlocks.checkpointCore().value())
					|| !state.hasProperty(CheckpointCoreBlock.LIT)
					|| !state.getValue(CheckpointCoreBlock.LIT)) {
				continue;
			}
			Vec3 center = Vec3.atCenterOf(location.pos());
			for (int index = 0; index < count; index++) {
				Vec3 offset = new Vec3(
						level.random.nextDouble() * 2.0 - 1.0,
						level.random.nextDouble() * 2.0 - 1.0,
						level.random.nextDouble() * 2.0 - 1.0
				);
				if (offset.lengthSqr() < 1.0E-4) {
					offset = new Vec3(1.0, 0.0, 0.0);
				}
				offset = offset.normalize().scale(0.9 + level.random.nextDouble() * 1.3);
				Vec3 start = center.add(offset);
				Vec3 velocity = center.subtract(start).normalize().scale(speed);
				level.sendParticles(
						RECORDER_PARTICLE,
						start.x,
						start.y,
						start.z,
						0,
						velocity.x,
						velocity.y,
						velocity.z,
						1.0
				);
			}
		}
	}
	private static ConditionResult evaluateConditions(MinecraftServer server, CheckpointStateCoordinator coordinator) {
		Map<UUID, RecorderSelectionService.PlayerPosition> selectionPlayers = new LinkedHashMap<>();
		Map<UUID, Vec3> positions = new LinkedHashMap<>();
		Map<RecorderLocation, RecorderSelectionService.RecorderCandidate<RecorderLocation>> candidates = new LinkedHashMap<>();
		for (UUID playerId : coordinator.state().roster().members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null || player.isSleeping() || player.getHealth() < player.getMaxHealth()
					|| player.getFoodData().getFoodLevel() < 20 || !safeFromHostiles(player)) {
				return ConditionResult.failed();
			}
			Entity rootVehicle = player.getRootVehicle();
			if (rootVehicle != player && rootVehicle.getDeltaMovement().lengthSqr() > 1.0E-6) {
				return ConditionResult.failed();
			}
			String dimension = player.serverLevel().dimension().location().toString();
			Vec3 position = player.position();
			positions.put(playerId, position);
			selectionPlayers.put(playerId, new RecorderSelectionService.PlayerPosition(
					dimension,
					position.x,
					position.y,
					position.z
			));
			collectLitRecorders(player.serverLevel(), player.blockPosition(), candidates);
		}
		if (selectionPlayers.isEmpty()) {
			return ConditionResult.failed();
		}
		Set<RecorderLocation> selection = SELECTION_SERVICE.selectNearestUnion(
				selectionPlayers,
				candidates.values(),
				RECORDER_RANGE
		);
		return selection.isEmpty()
				? ConditionResult.failed()
				: new ConditionResult(true, Map.copyOf(positions), Set.copyOf(selection));
	}

	private static void collectLitRecorders(
			ServerLevel level,
			BlockPos center,
			Map<RecorderLocation, RecorderSelectionService.RecorderCandidate<RecorderLocation>> candidates
	) {
		BlockPos min = center.offset(-8, -8, -8);
		BlockPos max = center.offset(8, 8, 8);
		for (BlockPos mutable : BlockPos.betweenClosed(min, max)) {
			BlockPos pos = mutable.immutable();
			BlockState state = level.getBlockState(pos);
			if (!state.is(ModBlocks.checkpointCore().value())
					|| !state.hasProperty(CheckpointCoreBlock.LIT)
					|| !state.getValue(CheckpointCoreBlock.LIT)) {
				continue;
			}
			RecorderLocation location = new RecorderLocation(level.dimension(), pos);
			candidates.putIfAbsent(location, new RecorderSelectionService.RecorderCandidate<>(
					location,
					level.dimension().location().toString(),
					pos.getX() + 0.5,
					pos.getY() + 0.5,
					pos.getZ() + 0.5
			));
		}
	}

	private static boolean safeFromHostiles(ServerPlayer player) {
		AABB search = player.getBoundingBox().inflate(8.0, 5.0, 8.0);
		return player.serverLevel().getEntitiesOfClass(
				Monster.class,
				search,
				monster -> monster.isPreventingPlayerRest(player)
		).isEmpty();
	}

	private record RecorderLocation(ResourceKey<Level> dimension, BlockPos pos) {
	}

	private record ConditionResult(boolean satisfied, Map<UUID, Vec3> players, Set<RecorderLocation> selection) {
		static ConditionResult failed() {
			return new ConditionResult(false, Map.of(), Set.of());
		}
	}

	private record WorldContext(ControlRootLayout layout, ControlMetadata metadata) {
	}

	private record CaptureCompletion(long generation, Runnable action) {
	}

	private record CaptureOutcome(CheckpointCaptureService.CaptureResult result, Throwable failure) {
	}
}
