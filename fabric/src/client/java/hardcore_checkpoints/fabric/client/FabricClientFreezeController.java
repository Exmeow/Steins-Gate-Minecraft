package hardcore_checkpoints.fabric.client;

import hardcore_checkpoints.fabric.client.state.CheckpointClientState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class FabricClientFreezeController {
	private FabricClientFreezeController() {
	}

	public static void initialize() {
		ClientTickEvents.END_CLIENT_TICK.register(FabricClientFreezeController::apply);
	}

	private static void apply(Minecraft client) {
		if (!CheckpointClientState.isServerForcedPause()) {
			return;
		}
		setReleased(
				client.options.keyUp,
				client.options.keyDown,
				client.options.keyLeft,
				client.options.keyRight,
				client.options.keyJump,
				client.options.keyShift,
				client.options.keySprint,
				client.options.keyAttack,
				client.options.keyUse,
				client.options.keyDrop,
				client.options.keySwapOffhand,
				client.options.keyPickItem
		);
		if (client.player != null) {
			client.player.setDeltaMovement(Vec3.ZERO);
			Entity vehicle = client.player.getVehicle();
			if (vehicle != null) {
				vehicle.setDeltaMovement(Vec3.ZERO);
			}
		}
		if (client.gameMode != null) {
			client.gameMode.stopDestroyBlock();
		}
	}

	private static void setReleased(KeyMapping... mappings) {
		for (KeyMapping mapping : mappings) {
			mapping.setDown(false);
		}
	}
}
