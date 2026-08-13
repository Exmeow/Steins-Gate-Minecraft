package hardcore_checkpoints.fabric.supervisor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import hardcore_checkpoints.HardcoreCheckpoints;
import hardcore_checkpoints.checkpoint.CheckpointPointer;
import hardcore_checkpoints.checkpoint.SnapshotVerifier;
import hardcore_checkpoints.control.WorldControlRuntime;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.fabric.network.FabricCheckpointNetworking;
import hardcore_checkpoints.fabric.platform.FabricGameplayFreezeController;
import hardcore_checkpoints.fabric.recovery.FabricDeathRecoveryIntegration;
import hardcore_checkpoints.recovery.DeathTransaction;
import hardcore_checkpoints.server.state.CheckpointPhase;
import hardcore_checkpoints.server.state.CheckpointServerRuntime;
import hardcore_checkpoints.supervisor.protocol.SupervisorEnvironment;
import hardcore_checkpoints.supervisor.protocol.SupervisorHealthReport;
import hardcore_checkpoints.supervisor.protocol.SupervisorRestartRequest;
import hardcore_checkpoints.supervisor.protocol.SupervisorRollbackRequest;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Minecraft 进程与本机监督程序之间的认证控制桥。
 *
 * <p>控制地址和令牌只从完整的监督环境读取，端点必须绑定回环地址。启动握手成功后按固定周期
 * 发送健康报告；心跳丢失会立即撤销健康状态并重新主张入场门禁。</p>
 *
 * <p>回档和受控重启都先由监督程序持久化接受，再执行 Minecraft 停服回调，避免无人接管的停服。</p>
 */
public final class FabricSupervisorBridge {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();
	private static final SnapshotVerifier VERIFIER = new SnapshotVerifier(new AtomicFileStore());

	// 连接和健康标记会被 HTTP 完成线程读取，因此使用 volatile 发布最新值。
	private static volatile SupervisorConnection connection;
	private static volatile boolean admissionBlocked;
	private static volatile boolean healthy;
	private static boolean invalidEnvironment;

	private FabricSupervisorBridge() {
	}

	public static void initialize() {
		ServerLifecycleEvents.SERVER_STARTING.register(FabricSupervisorBridge::serverStarting);
		ServerLifecycleEvents.SERVER_STARTED.register(FabricSupervisorBridge::serverStarted);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
	}

	public static boolean isConfigured() {
		return connection != null;
	}

	public static boolean isAdmissionBlocked() {
		return admissionBlocked;
	}

	public static boolean isHealthy() {
		return healthy;
	}

	/**
	 * 在破坏性管理操作前执行同步存活预检，避免仅凭最近一次心跳误判监督程序仍可接管。
	 */
	public static boolean isResponsive() {
		SupervisorConnection current = connection;
		if (!healthy || current == null) {
			return false;
		}
		try {
			return post(current, "/internal/ping", new Object())
					.get(3, java.util.concurrent.TimeUnit.SECONDS)
					.statusCode() == 204;
		} catch (Exception exception) {
			HardcoreCheckpoints.LOGGER.warn("Supervisor liveness preflight failed", exception);
			return false;
		}
	}

	public static UUID sessionId() {
		return connection == null ? null : connection.sessionId();
	}

	public static int statusPort() {
		return connection == null ? 0 : connection.statusPort();
	}

	/**
	 * 请求死亡回档并停服。专服必须先向监督程序提交事务；单人模式仅执行本地停服回调。
	 */
	public static void requestRollbackAndStop(
			MinecraftServer server,
			DeathTransaction transaction,
			Runnable beforeStop
	) {
		SupervisorConnection current = connection;
		if (server.isSingleplayer() || current == null) {
			server.execute(() -> {
				try {
					beforeStop.run();
				} finally {
					server.halt(false);
				}
			});
			return;
		}
		admissionBlocked = true;
		FabricGameplayFreezeController.ensureFrozen(server);
		SupervisorRollbackRequest request = SupervisorRollbackRequest.create(current.sessionId(), transaction);
		post(current, "/internal/rollback", request).whenComplete((response, failure) -> server.execute(() -> {
			if (failure != null || response.statusCode() != 202) {
				HardcoreCheckpoints.LOGGER.error(
						"Supervisor rejected or missed committed death rollback request (status={})",
						response == null ? "unavailable" : response.statusCode(),
						failure
				);
			}
			try {
				beforeStop.run();
			} finally {
				server.halt(false);
			}
		}));
	}

	/**
	 * 请求非回档型受控重启。监督程序未明确接受时保持冻结且不自行停服。
	 */
	public static void requestControlledRestartAndStop(
			MinecraftServer server,
			SupervisorRestartRequest.Reason reason
	) {
		SupervisorConnection current = connection;
		if (server.isSingleplayer() || current == null) {
			server.execute(() -> server.halt(false));
			return;
		}
		admissionBlocked = true;
		FabricGameplayFreezeController.ensureFrozen(server);
		SupervisorRestartRequest request = new SupervisorRestartRequest(1, current.sessionId(), reason);
		post(current, "/internal/restart", request).whenComplete((response, failure) -> server.execute(() -> {
			if (failure != null || response.statusCode() != 202) {
				HardcoreCheckpoints.LOGGER.error(
						"Supervisor rejected controlled restart {} (status={})",
						reason,
						response == null ? "unavailable" : response.statusCode(),
						failure
				);
				return;
			}
			server.halt(false);
		}));
	}

	/** 启动早期先读取监督环境；已启用专服默认保持冻结，直到认证握手完成。 */
	private static void serverStarting(MinecraftServer server) {
		EnvironmentResult environment = readEnvironment();
		connection = environment.connection();
		invalidEnvironment = environment.invalid();
		healthy = false;
		boolean enabled = WorldControlRuntime.current()
				.flatMap(result -> result.optionalMetadata())
				.map(metadata -> metadata.featureEnabled())
				.orElse(false);
		admissionBlocked = !server.isSingleplayer() && enabled;
		if (admissionBlocked && connection == null) {
			HardcoreCheckpoints.LOGGER.error(
					"Enabled dedicated world has no valid Hardcore Checkpoints supervisor environment; gameplay remains frozen"
			);
		}
	}

	/**
	 * 启动后验证快照并发送首个健康报告；环境部分缺失时禁止自动交接第二个监督器。
	 */
	private static void serverStarted(MinecraftServer server) {
		if (server.isSingleplayer()) {
			return;
		}
		if (connection == null && invalidEnvironment) {
			HardcoreCheckpoints.LOGGER.error("Refusing supervisor auto-launch because supervisor environment variables are incomplete or invalid");
			return;
		}
		if (connection == null) {
			boolean featureEnabled = WorldControlRuntime.current()
					.flatMap(result -> result.optionalMetadata())
					.map(metadata -> metadata.featureEnabled())
					.orElse(false);
			admissionBlocked = true;
			FabricGameplayFreezeController.ensureFrozen(server);
			try {
				boolean launched = FabricSupervisorAutoLauncher.launchAndStop(server);
				if (!launched) {
					HardcoreCheckpoints.LOGGER.warn("Dedicated supervisor auto-launch is disabled");
					releaseAutoLaunchGate(server, featureEnabled);
				}
			} catch (Exception exception) {
				HardcoreCheckpoints.LOGGER.error("Failed to auto-launch the Hardcore Checkpoints supervisor", exception);
				releaseAutoLaunchGate(server, featureEnabled);
			}
			return;
		}
		try {
			var result = WorldControlRuntime.current().orElseThrow();
			var layout = result.optionalLayout().orElseThrow();
			var metadata = result.optionalMetadata().orElseThrow();
			var coordinator = CheckpointServerRuntime.coordinator().orElse(null);
			boolean hasCheckpoint = java.nio.file.Files.isRegularFile(
					layout.instanceRoot().resolve("checkpoint-pointer.json")
			);
			CheckpointPointer pointer = hasCheckpoint ? VERIFIER.verifyPublished(layout, metadata.identity()) : null;
			if (metadata.featureEnabled()) {
				FabricGameplayFreezeController.ensureFrozen(server);
			}
			SupervisorHealthReport report = SupervisorHealthReport.create(
					connection.sessionId(),
					metadata.identity(),
					metadata.featureEnabled(),
					pointer == null ? null : pointer.latestCheckpointId(),
					hasCheckpoint,
					server.tickRateManager().isFrozen(),
					coordinator == null ? CheckpointPhase.FEATURE_DISABLED.name() : coordinator.state().phase().name()
			);
			submitHealth(server, report, 1);
		} catch (Exception exception) {
			HardcoreCheckpoints.LOGGER.error("Failed to prepare supervisor health report; gameplay remains frozen", exception);
		}
	}

	/** 自动交接失败时，仅未启用世界可以释放临时门禁；启用状态必须继续冻结。 */
	private static void releaseAutoLaunchGate(MinecraftServer server, boolean featureEnabled) {
		if (featureEnabled) {
			return;
		}
		admissionBlocked = false;
		FabricGameplayFreezeController.release(server);
	}
	/**
	 * 发送首个健康握手；失败时指数外的固定重试仍保持入场冻结，成功后才启动心跳。
	 */
	private static void submitHealth(MinecraftServer server, SupervisorHealthReport report, int attempt) {
		SupervisorConnection current = connection;
		if (current == null || healthy) {
			return;
		}
		post(current, "/internal/health", report).whenComplete((response, failure) -> {
			if (failure != null || response.statusCode() != 202) {
				if (attempt == 1 || attempt % 5 == 0) {
					HardcoreCheckpoints.LOGGER.warn(
							"Supervisor health handshake attempt {} failed (status={}); gameplay remains frozen",
							attempt,
							response == null ? "unavailable" : response.statusCode(),
							failure
					);
				}
				java.util.concurrent.CompletableFuture.delayedExecutor(2, java.util.concurrent.TimeUnit.SECONDS)
						.execute(() -> submitHealth(server, report, attempt + 1));
				return;
			}
			server.execute(() -> {
				try {
					FabricDeathRecoveryIntegration.completeSupervisorRecovery(server);
					healthy = true;
					admissionBlocked = false;
					CheckpointServerRuntime.coordinator().ifPresent(state ->
							FabricCheckpointNetworking.broadcastCurrentState(server, state));
					HardcoreCheckpoints.LOGGER.info("Supervisor health handshake completed for session {}", current.sessionId());
					scheduleHeartbeat(server, report, current);
				} catch (Exception exception) {
					HardcoreCheckpoints.LOGGER.error("Failed to commit server state after supervisor health acceptance", exception);
					java.util.concurrent.CompletableFuture.delayedExecutor(2, java.util.concurrent.TimeUnit.SECONDS)
							.execute(() -> submitHealth(server, report, attempt + 1));
				}
			});
		});
	}

	/**
	 * 同一会话每五秒探活。失活时除死亡倒计时外立即恢复冻结，并使用原会话重新握手。
	 */
	private static void scheduleHeartbeat(
			MinecraftServer server,
			SupervisorHealthReport report,
			SupervisorConnection expected
	) {
		java.util.concurrent.CompletableFuture.delayedExecutor(5, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
			if (connection != expected || !healthy) {
				return;
			}
			post(expected, "/internal/ping", new Object()).whenComplete((response, failure) -> {
				if (failure == null && response.statusCode() == 204) {
					scheduleHeartbeat(server, report, expected);
					return;
				}
				server.execute(() -> {
					if (connection != expected) {
						return;
					}
					healthy = false;
					boolean deathCountdown = CheckpointServerRuntime.coordinator()
							.map(coordinator -> coordinator.state().phase() == CheckpointPhase.DEATH_COUNTDOWN)
							.orElse(false);
					admissionBlocked = report.featureEnabled() && !deathCountdown;
					if (admissionBlocked) {
						FabricGameplayFreezeController.ensureFrozen(server);
					}
					CheckpointServerRuntime.coordinator().ifPresent(state ->
							FabricCheckpointNetworking.broadcastCurrentState(server, state));
					HardcoreCheckpoints.LOGGER.error(
							"Lost supervisor liveness (status={}); admission remains blocked until the same session recovers",
							response == null ? "unavailable" : response.statusCode(),
							failure
					);
					submitHealth(server, report, 1);
				});
			});
		});
	}

	/** 所有控制请求携带环境令牌，仅发送到已验证的回环控制地址。 */
	private static java.util.concurrent.CompletableFuture<HttpResponse<String>> post(
			SupervisorConnection target,
			String path,
			Object body
	) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(target.controlUrl() + path))
				.timeout(Duration.ofSeconds(10))
				.header("Authorization", "Bearer " + target.token())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
				.build();
		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * 四个变量必须全无或全部有效：全无允许自动交接，部分存在视为部署错误并拒绝启动第二监督器。
	 */
	private static EnvironmentResult readEnvironment() {
		String session = System.getenv(SupervisorEnvironment.SESSION_ID);
		String controlUrl = System.getenv(SupervisorEnvironment.CONTROL_URL);
		String token = System.getenv(SupervisorEnvironment.CONTROL_TOKEN);
		String statusPort = System.getenv(SupervisorEnvironment.STATUS_PORT);
		if (session == null && controlUrl == null && token == null && statusPort == null) {
			return new EnvironmentResult(null, false);
		}
		if (session == null || controlUrl == null || token == null || statusPort == null) {
			return new EnvironmentResult(null, true);
		}
		try {
			URI uri = URI.create(controlUrl);
			if (!"http".equals(uri.getScheme())
					|| uri.getHost() == null
					|| !("127.0.0.1".equals(uri.getHost()) || "::1".equals(uri.getHost()))) {
				throw new IllegalArgumentException("Supervisor control URL is not loopback HTTP");
			}
			int port = Integer.parseInt(statusPort);
			if (port < 1 || port > 65535 || token.length() < 32) {
				throw new IllegalArgumentException("Invalid supervisor port or token");
			}
			return new EnvironmentResult(
					new SupervisorConnection(UUID.fromString(session), uri.toString(), token, port),
					false
			);
		} catch (IllegalArgumentException exception) {
			HardcoreCheckpoints.LOGGER.error("Invalid Hardcore Checkpoints supervisor environment", exception);
			return new EnvironmentResult(null, true);
		}
	}

	private static void clear() {
		connection = null;
		admissionBlocked = false;
		healthy = false;
		invalidEnvironment = false;
	}

	private record EnvironmentResult(SupervisorConnection connection, boolean invalid) {
	}

	private record SupervisorConnection(UUID sessionId, String controlUrl, String token, int statusPort) {
	}
}
