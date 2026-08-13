package hardcore_checkpoints.fabric.platform;

import hardcore_checkpoints.HardcoreCheckpoints;

import hardcore_checkpoints.fabric.supervisor.FabricSupervisorBridge;
import hardcore_checkpoints.server.state.CheckpointServerRuntime;
import hardcore_checkpoints.server.state.CheckpointServerState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class FabricGameplayFreezeController {
	private static boolean modOwnedFreeze;
	private static boolean suppressFreezeForShutdown;
	private static MinecraftServer activeServer;

	private FabricGameplayFreezeController() {
	}

	public static void initialize() {
		ServerTickEvents.START_SERVER_TICK.register(FabricGameplayFreezeController::apply);
	}

	public static boolean allowsGameplay() {
		if (FabricSupervisorBridge.isAdmissionBlocked()) {
			return false;
		}
		return CheckpointServerRuntime.coordinator()
				.map(coordinator -> !shouldFreeze(activeServer, coordinator.state()))
				.orElse(true);
	}

	public static boolean isFrozen() {
		return !allowsGameplay();
	}

	public static void ensureFrozen(MinecraftServer server) {
		if (!server.tickRateManager().isFrozen()) {
			server.tickRateManager().setFrozen(true);
			modOwnedFreeze = true;
		}
	}

	private static void apply(MinecraftServer server) {
		activeServer = server;
		if (suppressFreezeForShutdown) {
			forceRelease(server);
			return;
		}
		CheckpointServerRuntime.coordinator().ifPresentOrElse(coordinator -> {
			CheckpointServerState state = coordinator.state();
			boolean shouldFreeze = shouldFreeze(server, state);
			if (shouldFreeze) {
				if (!server.tickRateManager().isFrozen()) {
					server.tickRateManager().setFrozen(true);
					modOwnedFreeze = true;
				}
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					player.setDeltaMovement(Vec3.ZERO);
					Entity vehicle = player.getVehicle();
					if (vehicle != null) {
						vehicle.setDeltaMovement(Vec3.ZERO);
					}
				}
			} else if (modOwnedFreeze) {
				release(server);
			}
		}, () -> {
			if (modOwnedFreeze) {
				release(server);
			}
		});
	}

	public static void releaseForShutdown(MinecraftServer server) {
		suppressFreezeForShutdown = true;
		forceRelease(server);
	}

	public static void release(MinecraftServer server) {
		if (CheckpointServerRuntime.coordinator()
				.map(coordinator -> shouldFreeze(server, coordinator.state()))
				.orElse(FabricSupervisorBridge.isAdmissionBlocked())) {
			return;
		}
		forceRelease(server);
	}

	private static void forceRelease(MinecraftServer server) {
		if (!modOwnedFreeze) {
			return;
		}
		if (server.tickRateManager().isFrozen()) {
			server.tickRateManager().setFrozen(false);
		}
		modOwnedFreeze = false;
		HardcoreCheckpoints.LOGGER.info("Released Hardcore Checkpoints gameplay freeze");
	}

	private static boolean shouldFreeze(MinecraftServer server, CheckpointServerState state) {
		if (FabricSupervisorBridge.isAdmissionBlocked()) {
			return true;
		}
		if (!state.featureEnabled()) {
			return state.phase().isGameplayFrozen();
		}
		if (state.phase() != hardcore_checkpoints.server.state.CheckpointPhase.DEATH_COUNTDOWN
				&& (server == null || !server.getPlayerList().getPlayers().stream()
						.map(ServerPlayer::getUUID)
						.collect(java.util.stream.Collectors.toSet())
						.containsAll(state.roster().members()))) {
			return true;
		}
		return state.phase().isGameplayFrozen();
	}

	public static void clear() {
		modOwnedFreeze = false;
		suppressFreezeForShutdown = false;
		activeServer = null;
	}
}
