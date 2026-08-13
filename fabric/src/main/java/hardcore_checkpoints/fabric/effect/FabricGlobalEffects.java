package hardcore_checkpoints.fabric.effect;

import hardcore_checkpoints.fabric.platform.FabricGameplayFreezeController;
import hardcore_checkpoints.fabric.supervisor.FabricSupervisorBridge;
import hardcore_checkpoints.registry.ModSounds;
import hardcore_checkpoints.server.state.CheckpointPhase;
import hardcore_checkpoints.server.state.CheckpointServerRuntime;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

public final class FabricGlobalEffects {
	private static boolean checkpointSoundPending;
	private static boolean rollbackSoundPending;

	private FabricGlobalEffects() {
	}

	public static void initialize() {
		ServerTickEvents.END_SERVER_TICK.register(FabricGlobalEffects::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
	}

	public static void queueCheckpointSuccess() {
		checkpointSoundPending = true;
	}

	public static void queueRollbackSuccess() {
		rollbackSoundPending = true;
	}

	private static void clear() {
		checkpointSoundPending = false;
		rollbackSoundPending = false;
	}

	private static void tick(MinecraftServer server) {
		if ((!checkpointSoundPending && !rollbackSoundPending)
				|| server.getPlayerList().getPlayers().isEmpty()
				|| FabricSupervisorBridge.isAdmissionBlocked()
				|| FabricGameplayFreezeController.isFrozen()
				|| CheckpointServerRuntime.coordinator()
						.map(coordinator -> coordinator.state().phase() != CheckpointPhase.RUNNING)
						.orElse(false)) {
			return;
		}
		if (rollbackSoundPending) {
			playForAll(server, ModSounds.ROLLBACK_SUCCESS);
			rollbackSoundPending = false;
			checkpointSoundPending = false;
			return;
		}
		playForAll(server, ModSounds.CHECKPOINT_SUCCESS);
		checkpointSoundPending = false;
	}

	private static void playForAll(MinecraftServer server, SoundEvent sound) {
		server.getPlayerList().getPlayers().forEach(player ->
				player.playNotifySound(sound, SoundSource.MASTER, 1.0F, 1.0F));
	}
}
