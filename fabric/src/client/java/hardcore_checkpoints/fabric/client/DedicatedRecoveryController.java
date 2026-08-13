package hardcore_checkpoints.fabric.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import hardcore_checkpoints.HardcoreCheckpoints;
import hardcore_checkpoints.fabric.client.screen.DedicatedRecoveryScreen;
import hardcore_checkpoints.fabric.client.state.CheckpointClientState;
import hardcore_checkpoints.server.network.ControlStateSnapshot;
import hardcore_checkpoints.server.network.payload.ProtocolChallengePayload;
import hardcore_checkpoints.server.state.CheckpointPhase;
import hardcore_checkpoints.supervisor.protocol.SupervisorStatusResponse;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DedicatedRecoveryController {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();
	private static final AtomicBoolean POLL_IN_FLIGHT = new AtomicBoolean();
	private static final AtomicBoolean RECONNECTING = new AtomicBoolean();

	private static SupervisorEndpoint endpoint;
	private static DedicatedTarget target;
	private static volatile SupervisorStatusResponse status;
	private static volatile boolean waiting;
	private static boolean recoveryConnectionClosed;
	private static boolean recoveryCycleObserved;
	private static long recoveryBaselineEpoch = -1L;
	private static long lastSupervisorEpoch = -1L;
	private static long nextPollNanos;

	private DedicatedRecoveryController() {
	}

	public static void initialize() {
		ClientTickEvents.END_CLIENT_TICK.register(DedicatedRecoveryController::tick);
	}

	public static void configure(ProtocolChallengePayload challenge) {
		boolean reconnectAttempt = RECONNECTING.get();
		if (!reconnectAttempt) {
			waiting = false;
			recoveryConnectionClosed = false;
			recoveryCycleObserved = false;
			recoveryBaselineEpoch = -1L;
			lastSupervisorEpoch = -1L;
			status = null;
			POLL_IN_FLIGHT.set(false);
			target = null;
		}
		if (challenge.statusPort() <= 0 || challenge.supervisorSessionId() == null) {
			endpoint = null;
			return;
		}
		endpoint = new SupervisorEndpoint(challenge.statusPort(), challenge.supervisorSessionId());
	}

	public static void enterPlay(ServerData serverData) {
		SupervisorEndpoint current = endpoint;
		if (current == null || serverData == null) {
			target = null;
			return;
		}
		ServerAddress address = ServerAddress.parseString(serverData.ip);
		String host = address.getHost();
		if (host.isBlank()) {
			target = null;
			return;
		}
		target = new DedicatedTarget(serverData, host, current.statusPort(), current.sessionId());
		waiting = false;
		recoveryConnectionClosed = false;
		RECONNECTING.set(false);
		nextPollNanos = 0L;
	}

	public static void onState(Minecraft minecraft, ControlStateSnapshot snapshot) {
		if (minecraft.hasSingleplayerServer()) {
			return;
		}
		if (snapshot.phase() == CheckpointPhase.DEATH_COUNTDOWN) {
			prefetchSupervisorStatus(minecraft);
			return;
		}
		if (snapshot.phase() == CheckpointPhase.ROLLBACK_PENDING
				|| snapshot.phase() == CheckpointPhase.RESTORING
				|| snapshot.phase() == CheckpointPhase.RECOVERY_FAILED) {
			beginWaiting(minecraft);
		}
	}

	public static void onDisconnected(Minecraft minecraft) {
		if (minecraft.hasSingleplayerServer()) {
			return;
		}
		CheckpointClientState.snapshot().ifPresent(snapshot -> {
			if (snapshot.phase().isDeathRecovery()) {
				recoveryConnectionClosed = true;
				beginWaiting(minecraft);
			}
		});
	}
	public static void onReconnectFailed(Minecraft minecraft) {
		if (target == null || !RECONNECTING.compareAndSet(true, false)) {
			return;
		}
		waiting = true;
		recoveryConnectionClosed = true;
		nextPollNanos = System.nanoTime() + Duration.ofSeconds(1).toNanos();
		minecraft.setScreen(new DedicatedRecoveryScreen());
	}


	public static SupervisorStatusResponse status() {
		return status;
	}

	public static void cancel(Minecraft minecraft) {
		waiting = false;
		recoveryConnectionClosed = false;
		recoveryCycleObserved = false;
		recoveryBaselineEpoch = -1L;
		status = null;
		POLL_IN_FLIGHT.set(false);
		RECONNECTING.set(false);
		minecraft.setScreen(new TitleScreen());
	}

	private static void prefetchSupervisorStatus(Minecraft minecraft) {
		long now = System.nanoTime();
		if (target == null || waiting || RECONNECTING.get() || now < nextPollNanos
				|| !POLL_IN_FLIGHT.compareAndSet(false, true)) {
			return;
		}
		nextPollNanos = now + Duration.ofSeconds(1).toNanos();
		requestSupervisorStatus(minecraft, true);
	}

	private static void beginWaiting(Minecraft minecraft) {
		if (target == null) {
			return;
		}
		if (!waiting) {
			waiting = true;
			recoveryCycleObserved = false;
			recoveryBaselineEpoch = lastSupervisorEpoch;
			status = null;
			RECONNECTING.set(false);
		}
		nextPollNanos = 0L;
		minecraft.setScreen(new DedicatedRecoveryScreen());
	}

	private static void tick(Minecraft minecraft) {
		if (RECONNECTING.get() && minecraft.screen instanceof DisconnectedScreen) {
			onReconnectFailed(minecraft);
			return;
		}
		if (!waiting) {
			CheckpointClientState.snapshot().ifPresent(snapshot -> {
				if (snapshot.phase() == CheckpointPhase.DEATH_COUNTDOWN) {
					prefetchSupervisorStatus(minecraft);
				}
			});
			return;
		}
		if (!canPollRecovery()) {
			return;
		}
		if (!(minecraft.screen instanceof DedicatedRecoveryScreen)
				&& !(minecraft.screen instanceof ConnectScreen)) {
			minecraft.setScreen(new DedicatedRecoveryScreen());
		}
		long now = System.nanoTime();
		if (now < nextPollNanos || !POLL_IN_FLIGHT.compareAndSet(false, true)) {
			return;
		}
		nextPollNanos = now + Duration.ofSeconds(1).toNanos();
		requestSupervisorStatus(minecraft, false);
	}

	private static void requestSupervisorStatus(Minecraft minecraft, boolean prefetchOnly) {
		try {
			URI statusUri = new URI("http", null, target.host(), target.statusPort(), "/status", null, null);
			HttpRequest request = HttpRequest.newBuilder(statusUri)
					.timeout(Duration.ofSeconds(5))
					.GET()
					.build();
			HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, failure) ->
					minecraft.execute(() -> handleStatusResponse(minecraft, response, failure, prefetchOnly)));
		} catch (Exception exception) {
			POLL_IN_FLIGHT.set(false);
			HardcoreCheckpoints.LOGGER.warn("Failed to create supervisor status request", exception);
		}
	}

	private static void handleStatusResponse(
			Minecraft minecraft,
			HttpResponse<String> response,
			Throwable failure,
			boolean prefetchOnly
	) {
		POLL_IN_FLIGHT.set(false);
		if (target == null || (!prefetchOnly && !waiting)) {
			return;
		}
		if (failure != null || response == null || response.statusCode() != 200) {
			return;
		}
		try {
			SupervisorStatusResponse received = GSON.fromJson(response.body(), SupervisorStatusResponse.class);
			if (received == null || !target.sessionId().equals(received.sessionId())) {
				throw new IllegalArgumentException("Supervisor status session does not match the Minecraft handshake");
			}
			lastSupervisorEpoch = Math.max(lastSupervisorEpoch, received.epoch());
			if (prefetchOnly) {
				return;
			}
			boolean readyToReconnect = observeRecoveryStatus(received);
			status = received;
			if (!readyToReconnect) {
				return;
			}
			if (RECONNECTING.compareAndSet(false, true)) {
				waiting = false;
				CheckpointClientState.clear();
				ConnectScreen.startConnecting(
						new TitleScreen(),
						minecraft,
						ServerAddress.parseString(target.serverData().ip),
						target.serverData(),
						false,
						null
				);
			}
		} catch (RuntimeException exception) {
			HardcoreCheckpoints.LOGGER.warn("Rejected invalid supervisor status response", exception);
		}
	}
	static boolean canPollRecovery() {
		return waiting && recoveryConnectionClosed && target != null && !RECONNECTING.get();
	}

	static boolean observeRecoveryStatus(SupervisorStatusResponse received) {
		if (recoveryBaselineEpoch < 0L) {
			recoveryBaselineEpoch = received.epoch();
		}
		if (!"READY_TO_CONNECT".equals(received.phase()) || received.epoch() > recoveryBaselineEpoch) {
			recoveryCycleObserved = true;
		}
		return recoveryCycleObserved && "READY_TO_CONNECT".equals(received.phase());
	}


	private record SupervisorEndpoint(int statusPort, UUID sessionId) {
	}

	private record DedicatedTarget(ServerData serverData, String host, int statusPort, UUID sessionId) {
	}
}
