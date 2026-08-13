package hardcore_checkpoints.fabric.client.state;

import hardcore_checkpoints.HardcoreCheckpoints;
import hardcore_checkpoints.server.network.ControlAction;
import hardcore_checkpoints.server.network.ControlStateSnapshot;
import hardcore_checkpoints.server.network.payload.ControlRequestPayload;
import hardcore_checkpoints.server.state.CheckpointPhase;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class CheckpointClientState {
	private static final AtomicReference<ControlStateSnapshot> SNAPSHOT = new AtomicReference<>();
	private static volatile boolean configuring;
	private static volatile long snapshotReceivedNanos;

	private CheckpointClientState() {
	}

	public static boolean apply(ControlStateSnapshot snapshot, boolean configurationPhase) {
		long receivedNanos = System.nanoTime();
		ControlStateSnapshot previous = SNAPSHOT.getAndSet(snapshot);
		snapshotReceivedNanos = receivedNanos;
		configuring = configurationPhase;
		boolean enteredRunning = previous != null
				&& previous.phase() != snapshot.phase()
				&& snapshot.phase() == CheckpointPhase.RUNNING;
		if (previous == null || previous.phase() != snapshot.phase()) {
			HardcoreCheckpoints.LOGGER.info(
					"Client checkpoint phase changed: {} -> {} (rosterVersion={}, epoch={}, networkStage={})",
					previous == null ? "NONE" : previous.phase(),
					snapshot.phase(),
					snapshot.rosterVersion(),
					snapshot.epochId(),
					configurationPhase ? "CONFIGURATION" : "PLAY"
			);
		}
		return enteredRunning;
	}

	public static void enterPlay() {
		// Configuration snapshots survive the protocol transition; only the transport stage changes here.
		configuring = false;
	}

	public static Optional<ControlStateSnapshot> snapshot() {
		return Optional.ofNullable(SNAPSHOT.get());
	}

	public static long countdownMillis() {
		ControlStateSnapshot snapshot = SNAPSHOT.get();
		if (snapshot == null || snapshot.countdownMillis() <= 0L) {
			return 0L;
		}
		long elapsedNanos = Math.max(0L, System.nanoTime() - snapshotReceivedNanos);
		long elapsedMillis = elapsedNanos / 1_000_000L;
		return Math.max(0L, snapshot.countdownMillis() - elapsedMillis);
	}
	public static boolean isConfiguring() {
		return configuring;
	}

	public static boolean isServerForcedPause() {
		ControlStateSnapshot snapshot = SNAPSHOT.get();
		return snapshot != null && snapshot.rosterMember() && snapshot.phase().isGameplayFrozen();
	}

	public static void send(ControlAction action, UUID targetPlayerId) {
		ControlStateSnapshot snapshot = SNAPSHOT.get();
		if (snapshot == null) {
			HardcoreCheckpoints.LOGGER.warn("Cannot send checkpoint action {} without a server snapshot", action);
			return;
		}
		ControlRequestPayload request = new ControlRequestPayload(
				snapshot.controlSessionId(),
				UUID.randomUUID(),
				action,
				targetPlayerId,
				snapshot.rosterVersion(),
				snapshot.epochId()
		);

		// Fabric throws when a stage-specific channel API is queried outside that stage, so probe defensively.
		try {
			if (ClientPlayNetworking.canSend(ControlRequestPayload.TYPE)) {
				ClientPlayNetworking.send(request);
				HardcoreCheckpoints.LOGGER.info("Sent checkpoint action {} over Play", action);
				return;
			}
		} catch (IllegalStateException ignored) {
			// The connection is still in Configuration; try that transport below.
		}
		if (configuring) {
			try {
				if (ClientConfigurationNetworking.canSend(ControlRequestPayload.TYPE)) {
					ClientConfigurationNetworking.send(request);
					HardcoreCheckpoints.LOGGER.info("Sent checkpoint action {} over Configuration", action);
					return;
				}
			} catch (IllegalStateException exception) {
				HardcoreCheckpoints.LOGGER.warn("Configuration transport rejected checkpoint action {}", action, exception);
				return;
			}
		}
		HardcoreCheckpoints.LOGGER.warn(
				"No checkpoint control channel is available for action {} (configuring={})",
				action,
				configuring
		);
	}

	public static void clear() {
		SNAPSHOT.set(null);
		snapshotReceivedNanos = 0L;
		configuring = false;
	}
}
