package hardcore_checkpoints.fabric.client.screen;

import hardcore_checkpoints.HardcoreCheckpoints;
import hardcore_checkpoints.fabric.client.state.CheckpointClientState;
import hardcore_checkpoints.fabric.platform.FabricGameplayFreezeController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicBoolean;

final class CheckpointScreenActions {
	private static final Component SAVING_LEVEL = Component.translatable("menu.savingLevel");
	private static final AtomicBoolean DISCONNECTING = new AtomicBoolean();

	private CheckpointScreenActions() {
	}

	static boolean isSingleplayer(Minecraft minecraft) {
		return minecraft != null && minecraft.hasSingleplayerServer();
	}

	static Component exitLabel(Minecraft minecraft) {
		return Component.translatable(isSingleplayer(minecraft)
				? "screen.hardcore_checkpoints.status.return_to_title"
				: "screen.hardcore_checkpoints.status.disconnect");
	}

	static void disconnect(Minecraft minecraft) {
		if (!DISCONNECTING.compareAndSet(false, true)) {
			return;
		}
		// Clear the snapshot immediately so no stale forced-pause screen can reopen while shutdown is queued.
		CheckpointClientState.clear();
		IntegratedServer integratedServer = minecraft.getSingleplayerServer();
		if (integratedServer == null) {
			disconnectNow(minecraft);
			return;
		}

		HardcoreCheckpoints.LOGGER.info("Releasing checkpoint freeze before leaving the integrated server");
		// Minecraft.disconnect waits synchronously for the integrated server, so the freeze must be released first.
		integratedServer.execute(() -> {
			FabricGameplayFreezeController.releaseForShutdown(integratedServer);
			// Configuration has no ClientLevel to disconnect, so explicitly request the integrated server to stop.
			integratedServer.halt(false);
			minecraft.execute(() -> disconnectNow(minecraft));
		});
	}

	private static void disconnectNow(Minecraft minecraft) {
		try {
			boolean localServer = minecraft.isLocalServer();
			if (minecraft.level != null) {
				// Vanilla PauseScreen disconnects the ClientLevel first; this notifies the integrated server to halt.
				minecraft.level.disconnect();
			}
			if (localServer) {
				minecraft.disconnect(new GenericMessageScreen(SAVING_LEVEL));
			} else {
				minecraft.disconnect();
			}
			minecraft.setScreen(new TitleScreen());
		} finally {
			DISCONNECTING.set(false);
		}
	}
}
