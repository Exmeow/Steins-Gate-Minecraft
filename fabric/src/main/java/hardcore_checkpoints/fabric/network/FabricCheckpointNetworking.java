package hardcore_checkpoints.fabric.network;

import com.mojang.authlib.GameProfile;
import hardcore_checkpoints.HardcoreCheckpoints;
import hardcore_checkpoints.control.WorldControlRuntime;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.fabric.platform.FabricGameplayFreezeController;
import hardcore_checkpoints.fabric.recovery.FabricDeathRecoveryIntegration;
import hardcore_checkpoints.fabric.supervisor.FabricSupervisorBridge;
import hardcore_checkpoints.server.admin.AdministratorRepository;
import hardcore_checkpoints.server.network.ConnectionStage;
import hardcore_checkpoints.server.network.ControlAction;
import hardcore_checkpoints.server.network.ControlProtocol;
import hardcore_checkpoints.server.network.ControlSession;
import hardcore_checkpoints.server.network.ControlSessionRegistry;
import hardcore_checkpoints.server.network.ControlStateSnapshot;
import hardcore_checkpoints.server.network.payload.ControlRequestPayload;
import hardcore_checkpoints.server.network.payload.ControlStatePayload;
import hardcore_checkpoints.server.network.payload.ProtocolChallengePayload;
import hardcore_checkpoints.server.network.payload.ProtocolHelloPayload;
import hardcore_checkpoints.server.state.CheckpointEvent;
import hardcore_checkpoints.server.state.CheckpointPhase;
import hardcore_checkpoints.server.state.CheckpointServerRuntime;
import hardcore_checkpoints.server.state.CheckpointServerState;
import hardcore_checkpoints.server.state.CheckpointStateCoordinator;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.mixin.networking.accessor.ServerCommonNetworkHandlerAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fabric 端检查点控制协议与连接生命周期适配器。
 *
 * <p>Configuration 阶段负责版本握手和入场许可，Play 阶段只处理已建立会话的控制请求。
 * 所有会改变状态机或会话表的回调最终都调度到服务器线程，避免网络线程与 tick 并发写状态。</p>
 *
 * <p>Configuration handler、玩家 UUID 和会话 UUID 共同构成连接所有权；仅凭玩家 UUID
 * 不足以接管重配置会话，否则重复登录可能复用旧连接。</p>
 */
public final class FabricCheckpointNetworking {
	private static final long HELLO_TIMEOUT_NANOS = java.time.Duration.ofSeconds(15).toNanos();
	private static final long TRANSITION_TIMEOUT_NANOS = java.time.Duration.ofSeconds(30).toNanos();
	// 会话表跨 Configuration/Play 使用；任何接管和关闭操作都必须同时校验 sessionId。
	private static final ControlSessionRegistry SESSIONS = new ControlSessionRegistry();
	private static final AdministratorRepository ADMINISTRATORS = new AdministratorRepository(new AtomicFileStore());
	// 按具体 handler 保存握手上下文，禁止同 UUID 的另一条连接借用旧状态。
	private static final Map<ServerConfigurationPacketListenerImpl, PendingConfiguration> CONFIGURING = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> TRANSITION_DEADLINES = new ConcurrentHashMap<>();
	private static final Set<UUID> JOIN_REQUESTS = ConcurrentHashMap.newKeySet();
	private static final AtomicLong JOIN_REQUEST_REVISION = new AtomicLong();
	private static long lastBroadcastSequence = -1;
	private static long lastBroadcastJoinRevision = -1;

	private FabricCheckpointNetworking() {
	}

	/**
	 * 注册协议载荷和生命周期回调。网络回调只负责取参，实际修改在服务器任务队列执行。
	 */
	public static void initialize() {
		registerPayloadTypes();
		ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> configureConnection(handler));
		ServerConfigurationConnectionEvents.DISCONNECT.register((handler, server) ->
				server.execute(() -> configurationDisconnected(handler, server)));
		ServerConfigurationNetworking.registerGlobalReceiver(ProtocolHelloPayload.TYPE, (payload, context) ->
				context.server().execute(() -> handleHello(context.networkHandler(), context.server(), payload)));
		ServerConfigurationNetworking.registerGlobalReceiver(ControlRequestPayload.TYPE, (payload, context) ->
				context.server().execute(() -> handleConfigurationRequest(context.networkHandler(), context.server(), payload)));
		ServerPlayNetworking.registerGlobalReceiver(ControlRequestPayload.TYPE, (payload, context) ->
				context.server().execute(() -> handlePlayRequest(context.player(), payload)));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> playJoined(handler));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				server.execute(() -> playDisconnected(handler)));
		ServerTickEvents.END_SERVER_TICK.register(FabricCheckpointNetworking::tick);
	}

	private static void registerPayloadTypes() {
		PayloadTypeRegistry.configurationS2C().register(ProtocolChallengePayload.TYPE, ProtocolChallengePayload.CODEC);
		PayloadTypeRegistry.configurationC2S().register(ProtocolHelloPayload.TYPE, ProtocolHelloPayload.CODEC);
		PayloadTypeRegistry.configurationS2C().register(ControlStatePayload.TYPE, ControlStatePayload.CODEC);
		PayloadTypeRegistry.configurationC2S().register(ControlRequestPayload.TYPE, ControlRequestPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(ControlStatePayload.TYPE, ControlStatePayload.CODEC.cast());
		PayloadTypeRegistry.playC2S().register(ControlRequestPayload.TYPE, ControlRequestPayload.CODEC.cast());
	}

	/**
	 * 建立普通 Configuration 握手。此处不会按 UUID 复用 RECONFIGURING 会话。
	 */
	private static void configureConnection(ServerConfigurationPacketListenerImpl handler) {
		if (!ServerConfigurationNetworking.canSend(handler, ProtocolChallengePayload.TYPE)) {
			handler.disconnect(Component.literal("Hardcore Checkpoints protocol is required on the client."));
			return;
		}
		UUID playerId = handler.getOwner().getId();
		boolean featureEnabled = WorldControlRuntime.current()
				.flatMap(result -> result.optionalMetadata())
				.map(metadata -> metadata.featureEnabled())
				.orElse(false);
		HardcoreCheckpoints.LOGGER.info(
				"Starting checkpoint Configuration handshake for {} (featureEnabled={})",
				handler.getOwner().getName(),
				featureEnabled
		);
		PendingConfiguration existing = CONFIGURING.get(handler);
		UUID sessionId = existing == null ? null : existing.sessionId();
		CONFIGURING.put(handler, new PendingConfiguration(
				playerId,
				handler,
				System.nanoTime() + HELLO_TIMEOUT_NANOS,
				false,
				sessionId
		));
		handler.addTask(new ConfigurationAdmissionTask(featureEnabled, modVersion()));
	}

	/**
	 * 将 switchToConfig 创建的新 handler 绑定到发起重配置的精确会话。
	 * 返回 false 表示这不是本模组拥有的重配置链，调用方应走普通 Configuration 流程。
	 */
	public static boolean configureReconfiguration(ServerConfigurationPacketListenerImpl handler) {
		UUID playerId = handler.getOwner().getId();
		ControlSession session = SESSIONS.find(playerId).orElse(null);
		if (session == null || session.stage() != ConnectionStage.RECONFIGURING) {
			return false;
		}
		CONFIGURING.put(handler, new PendingConfiguration(
				playerId,
				handler,
				System.nanoTime() + HELLO_TIMEOUT_NANOS,
				false,
				session.sessionId()
		));
		return true;
	}

	/**
	 * 验证客户端能力并创建控制会话。重配置只能接管 pending 中绑定的同一 sessionId；
	 * 普通重复连接会因已有健康会话而拒绝。
	 */
	private static void handleHello(
			ServerConfigurationPacketListenerImpl handler,
			MinecraftServer server,
			ProtocolHelloPayload hello
	) {
		UUID playerId = handler.getOwner().getId();
		PendingConfiguration pending = CONFIGURING.get(handler);
		if (pending == null) {
			handler.disconnect(Component.literal("Hardcore Checkpoints configuration session expired."));
			return;
		}
		if (!ControlProtocol.compatible(hello.protocolVersion(), hello.capabilities())) {
			handler.disconnect(Component.literal(
					"Hardcore Checkpoints protocol mismatch. Server=" + ControlProtocol.VERSION
							+ ", client=" + hello.protocolVersion()
			));
			return;
		}
		HardcoreCheckpoints.LOGGER.info(
				"Checkpoint protocol accepted for {} (clientVersion={}, loader={})",
				handler.getOwner().getName(),
				hello.implementationVersion(),
				hello.loaderId()
		);
		CONFIGURING.put(handler, new PendingConfiguration(playerId, handler, pending.deadlineNanos(), true, pending.sessionId()));

		CheckpointStateCoordinator coordinator = CheckpointServerRuntime.coordinator().orElse(null);
		if (coordinator == null || !coordinator.state().featureEnabled()) {
			handler.completeTask(ConfigurationAdmissionTask.TYPE);
			CONFIGURING.remove(handler);
			return;
		}

		if (server.isSingleplayer() && coordinator.state().roster().members().isEmpty()) {
			try {
				coordinator.submit(new CheckpointEvent.AddRosterMember(
						playerId,
						coordinator.state().roster().version(),
						UUID.randomUUID(),
						false
				));
			} catch (IOException exception) {
				handler.disconnect(Component.literal("Failed to initialize the singleplayer checkpoint roster."));
				return;
			}
		}

		boolean rosterMember = coordinator.state().roster().contains(playerId);
		boolean administrator = server.getProfilePermissions(handler.getOwner()) >= 4
				|| isIndependentAdministrator(playerId);
		ControlSession session = SESSIONS.find(playerId)
				.filter(existing -> existing.stage() == ConnectionStage.RECONFIGURING
						&& pending.sessionId() != null
						&& pending.sessionId().equals(existing.sessionId()))
				.map(existing -> SESSIONS.update(existing.withStage(ConnectionStage.CONTROL_CONNECTED, System.nanoTime())
						.withPermissions(administrator, rosterMember, System.nanoTime())))
				.orElseGet(() -> {
					ControlSessionRegistry.Registration registration = SESSIONS.register(
							playerId,
							administrator,
							rosterMember,
							System.nanoTime()
					);
					if (!registration.accepted()) {
						handler.disconnect(Component.literal("A healthy Hardcore Checkpoints session already exists for this account."));
						return null;
					}
					ConnectionStage stage = rosterMember
							? ConnectionStage.AWAITING_USER_PREPARE
							: ConnectionStage.CONTROL_CONNECTED;
					return SESSIONS.update(registration.session().withStage(stage, System.nanoTime()));
				});
		if (session == null) {
			return;
		}
		CONFIGURING.computeIfPresent(handler, (ignored, current) -> current.withSessionId(session.sessionId()));
		TRANSITION_DEADLINES.remove(playerId);
		sendConfigurationState(handler, server, session, coordinator);
	}

	/**
	 * Configuration 阶段仅允许入场前操作。PREPARE 成功后完成 Fabric 配置任务并切入 Play。
	 */
	private static void handleConfigurationRequest(
			ServerConfigurationPacketListenerImpl handler,
			MinecraftServer server,
			ControlRequestPayload request
	) {
		UUID playerId = handler.getOwner().getId();
		ControlSession session = validSession(playerId, request.controlSessionId());
		CheckpointStateCoordinator coordinator = CheckpointServerRuntime.coordinator().orElse(null);
		if (session == null || coordinator == null) {
			handler.disconnect(Component.literal("Stale Hardcore Checkpoints control session."));
			return;
		}
		if (request.action() == ControlAction.PREPARE) {
			if (!coordinator.state().roster().contains(playerId)
					|| coordinator.state().phase().isDeathRecovery()
					|| FabricSupervisorBridge.isAdmissionBlocked()) {
				HardcoreCheckpoints.LOGGER.warn(
						"Configuration PREPARE rejected for {} (rosterMember={}, phase={})",
						handler.getOwner().getName(),
						coordinator.state().roster().contains(playerId),
						coordinator.state().phase()
				);
				sendConfigurationState(handler, server, session, coordinator);
				return;
			}
			session = SESSIONS.update(session.withStage(ConnectionStage.READY_TO_JOIN, System.nanoTime()));
			TRANSITION_DEADLINES.put(playerId, System.nanoTime() + TRANSITION_TIMEOUT_NANOS);
			HardcoreCheckpoints.LOGGER.info(
					"Configuration PREPARE accepted for {}; transitioning to Play",
					handler.getOwner().getName()
			);
			sendConfigurationState(handler, server, session, coordinator);
			handler.completeTask(ConfigurationAdmissionTask.TYPE);
			return;
		}
		processControlRequest(server, playerId, session, request, coordinator);
		sendConfigurationState(handler, server, SESSIONS.find(playerId).orElse(session), coordinator);
	}

	/**
	 * 处理 Play 内 READY 与返回等候区。switchToConfig 前先记录断线语义和重配置阶段，
	 * 使旧 Play handler 的 DISCONNECT 回调可以识别这是协议切换而非物理断线。
	 */
	private static void handlePlayRequest(ServerPlayer player, ControlRequestPayload request) {
		UUID playerId = player.getUUID();
		ControlSession session = validSession(playerId, request.controlSessionId());
		CheckpointStateCoordinator coordinator = CheckpointServerRuntime.coordinator().orElse(null);
		if (session == null || coordinator == null) {
			return;
		}
		try {
			switch (request.action()) {
				case PREPARE -> {
					var ready = coordinator.submit(new CheckpointEvent.MarkReady(
							playerId,
							request.expectedEpochId(),
							request.expectedRosterVersion(),
							System.nanoTime()
					));
					if (ready.accepted()) {
						HardcoreCheckpoints.LOGGER.info(
								"Play READY accepted for {} (phase={}, epoch={})",
								player.getGameProfile().getName(),
								coordinator.state().phase(),
								coordinator.state().epoch().epochId()
						);
					}
				}
				case WITHDRAW_READY -> coordinator.submit(new CheckpointEvent.WithdrawReady(playerId, request.expectedEpochId()));
				case RETURN_TO_WAITING -> {
					if (player.getServer().isSingleplayer()) {
						HardcoreCheckpoints.LOGGER.warn("Ignored multiplayer waiting-room request from integrated server player {}", playerId);
					} else {
						CheckpointPhase phase = coordinator.state().phase();
						boolean deathCountdown = phase == CheckpointPhase.DEATH_COUNTDOWN;
						boolean ordinaryReturn = !phase.isDeathRecovery()
								&& phase != CheckpointPhase.SAVING
								&& phase != CheckpointPhase.RESTORING;
						if (deathCountdown || ordinaryReturn) {
							coordinator.submit(new CheckpointEvent.Disconnected(playerId));
							SESSIONS.update(session.withStage(ConnectionStage.RECONFIGURING, System.nanoTime()));
							TRANSITION_DEADLINES.put(playerId, System.nanoTime() + TRANSITION_TIMEOUT_NANOS);
							player.connection.switchToConfig();
							return;
						}
					}
				}
				default -> processControlRequest(player.getServer(), playerId, session, request, coordinator);
			}
			sendPlayState(player, session, coordinator);
		} catch (IOException exception) {
			HardcoreCheckpoints.LOGGER.error("Failed to process control request from {}", playerId, exception);
		}
	}

	/**
	 * 执行两阶段名单管理操作。expectedRosterVersion 提供乐观并发控制，管理员权限由会话快照判定。
	 */
	private static void processControlRequest(
			MinecraftServer server,
			UUID playerId,
			ControlSession session,
			ControlRequestPayload request,
			CheckpointStateCoordinator coordinator
	) {
		try {
			switch (request.action()) {
				case SUBMIT_JOIN_REQUEST -> addJoinRequest(playerId);
				case WITHDRAW_JOIN_REQUEST -> removeJoinRequest(playerId);
				case APPROVE_JOIN_REQUEST, MARK_PENDING_ADDITION -> {
					if (session.administrator() && request.targetPlayerId() != null) {
						var result = coordinator.submit(new CheckpointEvent.AddRosterMember(
								request.targetPlayerId(),
								request.expectedRosterVersion(),
								request.requestId(),
								request.action() == ControlAction.MARK_PENDING_ADDITION
						));
						if (result.accepted()) {
							removeJoinRequest(request.targetPlayerId());
						}
					}
				}
				case REJECT_JOIN_REQUEST -> {
					if (session.administrator() && request.targetPlayerId() != null) {
						removeJoinRequest(request.targetPlayerId());
					}
				}
				case CANCEL_PENDING_ADDITION, REMOVE_ROSTER_MEMBER -> {
					if (session.administrator() && request.targetPlayerId() != null) {
						var result = coordinator.submit(new CheckpointEvent.RemoveRosterMember(
								request.targetPlayerId(),
								request.expectedRosterVersion(),
								request.requestId()
						));
						if (result.accepted()) {
							reconfigureRemovedPlayer(server, request.targetPlayerId());
						}
					}
				}
				case REFRESH_STATE, PREPARE, WITHDRAW_READY, RETURN_TO_WAITING -> {
				}
			}
		} catch (IOException exception) {
			HardcoreCheckpoints.LOGGER.error("Failed to persist control request from {}", playerId, exception);
		}
	}

	/**
	 * 名单移除后把仍在 Play 的目标送回 Configuration，禁止其继续留在世界中。
	 */
	public static void reconfigureRemovedPlayer(MinecraftServer server, UUID playerId) {
		ServerPlayer target = server.getPlayerList().getPlayer(playerId);
		if (target == null) {
			return;
		}
		ControlSession targetSession = SESSIONS.find(playerId).orElse(null);
		if (targetSession == null) {
			target.connection.disconnect(Component.literal("Your Hardcore Checkpoints roster access was removed."));
			return;
		}
		try {
			SESSIONS.update(targetSession.withStage(ConnectionStage.RECONFIGURING, System.nanoTime()));
			TRANSITION_DEADLINES.put(playerId, System.nanoTime() + TRANSITION_TIMEOUT_NANOS);
			target.connection.switchToConfig();
		} catch (RuntimeException exception) {
			HardcoreCheckpoints.LOGGER.error("Failed to reconfigure removed roster member {}", playerId, exception);
			target.connection.disconnect(Component.literal("Your Hardcore Checkpoints roster access was removed."));
		}
	}

	/**
	 * Play JOIN 是入场完成的唯一确认点；只有此时会话才标记为 PLAY 并写入在线集合。
	 */
	private static void playJoined(ServerGamePacketListenerImpl handler) {
		UUID playerId = handler.player.getUUID();
		ControlSession session = SESSIONS.find(playerId).orElse(null);
		CheckpointStateCoordinator coordinator = CheckpointServerRuntime.coordinator().orElse(null);
		if (session == null || coordinator == null) {
			return;
		}
		TRANSITION_DEADLINES.remove(playerId);
		CONFIGURING.entrySet().removeIf(entry -> entry.getValue().sessionId() != null
				&& entry.getValue().sessionId().equals(session.sessionId()));
		SESSIONS.update(session.withStage(ConnectionStage.PLAY, System.nanoTime()));
		HardcoreCheckpoints.LOGGER.info(
				"Checkpoint control session entered Play for {} (session={})",
				handler.player.getGameProfile().getName(),
				session.sessionId()
		);
		try {
			coordinator.submit(new CheckpointEvent.PlayConnected(playerId));
			sendPlayState(handler.player, SESSIONS.find(playerId).orElse(session), coordinator);
			FabricDeathRecoveryIntegration.notifyRecoveredPlayer(handler.player);
		} catch (IOException exception) {
			HardcoreCheckpoints.LOGGER.error("Failed to record Play connection for {}", playerId, exception);
		}
	}

	/**
	 * 区分真实 TCP 断线与 Play -> Configuration 协议切换。
	 * 真实断线先物理冻结，再持久化 Disconnected；即使持久化失败，下一 tick 仍会按 PlayerList 校准。
	 */
	private static void playDisconnected(ServerGamePacketListenerImpl handler) {
		UUID playerId = handler.player.getUUID();
		ControlSession session = SESSIONS.find(playerId).orElse(null);
		boolean protocolSwitch = session != null
				&& ((ServerCommonNetworkHandlerAccessor) handler).getConnection().isConnected();
		if (session == null || protocolSwitch) {
			return;
		}
		CheckpointServerRuntime.coordinator().ifPresent(coordinator -> {
			if (coordinator.state().featureEnabled()
					&& coordinator.state().phase() != CheckpointPhase.DEATH_COUNTDOWN) {
				FabricGameplayFreezeController.ensureFrozen(handler.player.getServer());
			}
			try {
				coordinator.submit(new CheckpointEvent.Disconnected(playerId));
			} catch (IOException exception) {
				HardcoreCheckpoints.LOGGER.error("Failed to persist disconnect for {}", playerId, exception);
			}
		});
		SESSIONS.close(playerId, session.sessionId());
		removeJoinRequest(playerId);
	}

	/**
	 * 仅关闭此 Configuration handler 自己拥有的会话；正常切入 Play 时底层连接仍保持连接。
	 */
	private static void configurationDisconnected(ServerConfigurationPacketListenerImpl handler, MinecraftServer server) {
		PendingConfiguration pending = CONFIGURING.remove(handler);
		if (pending == null || pending.sessionId() == null) {
			return;
		}
		ControlSession session = SESSIONS.find(pending.playerId()).orElse(null);
		boolean protocolSwitch = session != null
				&& ((ServerCommonNetworkHandlerAccessor) handler).getConnection().isConnected();
		if (session == null || !session.sessionId().equals(pending.sessionId()) || protocolSwitch) {
			return;
		}
		SESSIONS.close(pending.playerId(), session.sessionId());
		TRANSITION_DEADLINES.remove(pending.playerId());
		removeJoinRequest(pending.playerId());
	}

	/**
	 * 清理握手/阶段超时，并以实际 PlayerList 为权威校准 PLAY 会话和状态机。
	 * 该校准是所有断连回调之外的最终一致性保障。
	 */
	public static void tick(MinecraftServer server) {
		long now = System.nanoTime();
		CONFIGURING.forEach((handler, pending) -> {
			if (!pending.helloReceived() && now >= pending.deadlineNanos()) {
				pending.handler().disconnect(Component.literal("Hardcore Checkpoints protocol handshake timed out."));
				CONFIGURING.remove(handler, pending);
				if (pending.sessionId() != null) {
					SESSIONS.close(pending.playerId(), pending.sessionId());
					TRANSITION_DEADLINES.remove(pending.playerId());
				}
			}
		});
		TRANSITION_DEADLINES.forEach((playerId, deadline) -> {
			if (now >= deadline) {
				SESSIONS.find(playerId).ifPresent(session -> SESSIONS.close(playerId, session.sessionId()));
				TRANSITION_DEADLINES.remove(playerId, deadline);
			}
		});
		CheckpointServerRuntime.coordinator().ifPresent(coordinator -> {
			try {
				if (coordinator.state().featureEnabled()) {
					Set<UUID> actualPlayPlayers = server.getPlayerList().getPlayers().stream()
							.map(ServerPlayer::getUUID)
							.collect(java.util.stream.Collectors.toUnmodifiableSet());
					SESSIONS.snapshot().forEach((playerId, session) -> {
						if (session.stage() == ConnectionStage.PLAY && !actualPlayPlayers.contains(playerId)) {
							SESSIONS.close(playerId, session.sessionId());
							TRANSITION_DEADLINES.remove(playerId);
							removeJoinRequest(playerId);
						}
					});
					coordinator.submit(new CheckpointEvent.PlayPresenceObserved(actualPlayPlayers));
					coordinator.submit(new CheckpointEvent.Tick(now));
				}
				long joinRevision = JOIN_REQUEST_REVISION.get();
				if (coordinator.state().stateSequence() != lastBroadcastSequence
						|| joinRevision != lastBroadcastJoinRevision) {
					broadcastCurrentState(server, coordinator);
				}
			} catch (IOException exception) {
				HardcoreCheckpoints.LOGGER.error("Failed to persist checkpoint server tick transition", exception);
			}
		});
	}

	public static void broadcastCurrentState(MinecraftServer server, CheckpointStateCoordinator coordinator) {
		broadcastState(server, coordinator);
		lastBroadcastSequence = coordinator.state().stateSequence();
		lastBroadcastJoinRevision = JOIN_REQUEST_REVISION.get();
	}

	private static void broadcastState(MinecraftServer server, CheckpointStateCoordinator coordinator) {
		CONFIGURING.values().forEach(pending -> {
			if (pending.sessionId() == null) {
				return;
			}
			SESSIONS.find(pending.playerId())
					.filter(session -> session.sessionId().equals(pending.sessionId()))
					.ifPresent(session -> sendConfigurationState(pending.handler(), server, session, coordinator));
		});
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			SESSIONS.find(player.getUUID()).ifPresent(session -> sendPlayState(player, session, coordinator));
		}
	}

	public static void clear() {
		SESSIONS.clear();
		CONFIGURING.clear();
		TRANSITION_DEADLINES.clear();
		JOIN_REQUESTS.clear();
		JOIN_REQUEST_REVISION.set(0);
		lastBroadcastSequence = -1;
		lastBroadcastJoinRevision = -1;
	}

	private static void addJoinRequest(UUID playerId) {
		if (JOIN_REQUESTS.add(playerId)) {
			JOIN_REQUEST_REVISION.incrementAndGet();
		}
	}

	private static void removeJoinRequest(UUID playerId) {
		if (JOIN_REQUESTS.remove(playerId)) {
			JOIN_REQUEST_REVISION.incrementAndGet();
		}
	}

	private static boolean isIndependentAdministrator(UUID playerId) {
		return WorldControlRuntime.current().flatMap(result -> result.optionalLayout()).map(layout -> {
			try {
				return ADMINISTRATORS.contains(layout, playerId);
			} catch (IOException exception) {
				HardcoreCheckpoints.LOGGER.error("Failed to read independent administrator list", exception);
				return false;
			}
		}).orElse(false);
	}

	private static ControlSession validSession(UUID playerId, UUID sessionId) {
		return SESSIONS.validate(playerId, sessionId) ? SESSIONS.find(playerId).orElse(null) : null;
	}

	private static void sendConfigurationState(
			ServerConfigurationPacketListenerImpl handler,
			MinecraftServer server,
			ControlSession session,
			CheckpointStateCoordinator coordinator
	) {
		if (ServerConfigurationNetworking.canSend(handler, ControlStatePayload.TYPE)) {
			ServerConfigurationNetworking.send(handler, new ControlStatePayload(snapshot(server, session, coordinator)));
		}
	}

	private static void sendPlayState(
			ServerPlayer player,
			ControlSession session,
			CheckpointStateCoordinator coordinator
	) {
		if (ServerPlayNetworking.canSend(player, ControlStatePayload.TYPE)) {
			ServerPlayNetworking.send(player, new ControlStatePayload(snapshot(player.getServer(), session, coordinator)));
		}
	}

	private static ControlStateSnapshot snapshot(
			MinecraftServer server,
			ControlSession session,
			CheckpointStateCoordinator coordinator
	) {
		var state = coordinator.state();
		var entries = new ArrayList<ControlStateSnapshot.RosterEntry>();
		for (UUID member : state.roster().members()) {
			String status = state.epoch().ready().playConnectedMembers().contains(member)
					? "PLAY"
					: SESSIONS.find(member).isPresent() ? "CONTROL" : "OFFLINE";
			String displayName = server.getProfileCache().get(member)
					.map(GameProfile::getName)
					.orElse(member.toString());
			entries.add(new ControlStateSnapshot.RosterEntry(
					member,
					displayName,
					status,
					state.epoch().ready().readyMembers().contains(member)
			));
		}
		var joinRequestEntries = new ArrayList<ControlStateSnapshot.RosterEntry>();
		if (session.administrator()) {
			for (UUID requester : JOIN_REQUESTS) {
				String displayName = server.getProfileCache().get(requester)
						.map(GameProfile::getName)
						.orElse(requester.toString());
				joinRequestEntries.add(new ControlStateSnapshot.RosterEntry(
						requester,
						displayName,
						"REQUESTED",
						false
				));
			}
		}
		boolean member = state.roster().contains(session.playerId());
		boolean deathGate = state.phase().isDeathRecovery();
		String statusMessage = JOIN_REQUESTS.contains(session.playerId()) ? "JOIN_REQUESTED" : state.phase().name();
		return new ControlStateSnapshot(
				session.sessionId(),
				state.phase(),
				state.epoch().epochId(),
				state.roster().version(),
				member,
				session.administrator(),
				state.epoch().ready().readyMembers().contains(session.playerId()),
				member && !deathGate && !FabricSupervisorBridge.isAdmissionBlocked(),
				deathGate,
				countdownMillis(state, System.nanoTime()),
				statusMessage,
				entries,
				joinRequestEntries
		);
	}

	private static long countdownMillis(CheckpointServerState state, long nowNanos) {
		if (state.phase() == CheckpointPhase.DEATH_COUNTDOWN) {
			return FabricDeathRecoveryIntegration.countdownMillis(nowNanos);
		}
		if (state.phase() != CheckpointPhase.START_COUNTDOWN) {
			return 0L;
		}
		Long deadlineNanos = state.epoch().ready().resumeDeadlineNanos();
		if (deadlineNanos == null) {
			return 0L;
		}
		long remainingNanos = deadlineNanos - nowNanos;
		return remainingNanos <= 0L ? 0L : (remainingNanos + 999_999L) / 1_000_000L;
	}
	private static String modVersion() {
		return FabricLoader.getInstance()
				.getModContainer(HardcoreCheckpoints.MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
	}

	private record PendingConfiguration(
			UUID playerId,
			ServerConfigurationPacketListenerImpl handler,
			long deadlineNanos,
			boolean helloReceived,
			UUID sessionId
	) {
		private PendingConfiguration withSessionId(UUID newSessionId) {
			return new PendingConfiguration(playerId, handler, deadlineNanos, helloReceived, newSessionId);
		}
	}
}
