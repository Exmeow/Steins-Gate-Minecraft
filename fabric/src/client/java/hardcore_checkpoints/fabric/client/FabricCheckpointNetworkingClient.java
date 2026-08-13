package hardcore_checkpoints.fabric.client;

import hardcore_checkpoints.HardcoreCheckpoints;
import hardcore_checkpoints.fabric.client.screen.CheckpointRosterScreen;
import hardcore_checkpoints.fabric.client.screen.DedicatedRecoveryScreen;
import hardcore_checkpoints.fabric.client.screen.CheckpointStatusScreen;
import hardcore_checkpoints.fabric.client.state.CheckpointClientState;
import hardcore_checkpoints.server.network.ControlAction;
import hardcore_checkpoints.server.network.ControlProtocol;
import hardcore_checkpoints.server.network.payload.ControlStatePayload;
import hardcore_checkpoints.server.network.payload.ProtocolChallengePayload;
import hardcore_checkpoints.server.network.payload.ProtocolHelloPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public final class FabricCheckpointNetworkingClient {
	private FabricCheckpointNetworkingClient() {
	}

	public static void initialize() {
		ClientConfigurationNetworking.registerGlobalReceiver(ProtocolChallengePayload.TYPE, (payload, context) -> {
			HardcoreCheckpoints.LOGGER.info(
					"Received checkpoint protocol challenge (featureEnabled={}, protocol={})",
					payload.featureEnabled(),
					ControlProtocol.VERSION
			);
			context.responseSender().sendPacket(new ProtocolHelloPayload(
					ControlProtocol.VERSION,
					ControlProtocol.REQUIRED_CAPABILITIES,
					modVersion(),
					"fabric"
			));
			context.client().execute(() -> {
				DedicatedRecoveryController.configure(payload);
				if (payload.featureEnabled()) {
					context.client().setScreen(new CheckpointStatusScreen());
				}
			});
		});
		ClientConfigurationNetworking.registerGlobalReceiver(ControlStatePayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					CheckpointClientState.apply(payload.snapshot(), true);
					SingleplayerRecoveryController.onState(context.client(), payload.snapshot());
					DedicatedRecoveryController.onState(context.client(), payload.snapshot());
					refreshControlScreen(context.client(), false);
				}));
		ClientPlayNetworking.registerGlobalReceiver(ControlStatePayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					boolean enteredRunning = CheckpointClientState.apply(payload.snapshot(), false);
					SingleplayerRecoveryController.onState(context.client(), payload.snapshot());
					DedicatedRecoveryController.onState(context.client(), payload.snapshot());
					refreshControlScreen(context.client(), enteredRunning);
				}));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
			DedicatedRecoveryController.enterPlay(handler.getServerData());
			SingleplayerRecoveryController.reset();
			CheckpointClientState.enterPlay();
			HardcoreCheckpoints.LOGGER.info("Checkpoint client entered Play; submitting automatic READY eligibility");
			// Configuration PREPARE admits the connection; this Play PREPARE marks readiness after the player exists.
			CheckpointClientState.snapshot().ifPresentOrElse(
					snapshot -> CheckpointClientState.send(ControlAction.PREPARE, null),
					() -> HardcoreCheckpoints.LOGGER.warn("Play JOIN completed without a checkpoint control snapshot")
			);
		}));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			HardcoreCheckpoints.LOGGER.info("Checkpoint Play connection closed");
			SingleplayerRecoveryController.onDisconnected(client);
			DedicatedRecoveryController.onDisconnected(client);
			CheckpointClientState.clear();
		}));
		ClientConfigurationConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			DedicatedRecoveryController.onDisconnected(client);
			DedicatedRecoveryController.onReconnectFailed(client);
			if (client.getConnection() == null) {
				CheckpointClientState.clear();
			}
		}));
		ClientLoginConnectionEvents.DISCONNECT.register((handler, client) ->
				client.execute(() -> DedicatedRecoveryController.onReconnectFailed(client)));
	}

	private static void refreshControlScreen(Minecraft client, boolean enteredRunning) {
		if (client.screen instanceof DedicatedRecoveryScreen) {
			return;
		}
		if (enteredRunning && (client.screen instanceof CheckpointStatusScreen
				|| client.screen instanceof CheckpointRosterScreen)) {
			client.setScreen(null);
			return;
		}
		if (client.screen instanceof CheckpointRosterScreen) {
			client.setScreen(new CheckpointRosterScreen(0));
		} else if (client.screen instanceof CheckpointStatusScreen
				|| CheckpointClientState.isConfiguring()
				|| CheckpointClientState.isServerForcedPause()) {
			client.setScreen(new CheckpointStatusScreen());
		}
	}

	private static String modVersion() {
		return FabricLoader.getInstance()
				.getModContainer("hardcore_checkpoints")
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
	}
}
