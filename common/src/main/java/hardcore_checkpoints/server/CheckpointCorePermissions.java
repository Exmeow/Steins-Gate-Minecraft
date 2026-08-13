package hardcore_checkpoints.server;

import hardcore_checkpoints.server.state.CheckpointServerRuntime;
import hardcore_checkpoints.server.state.ServerMutationGate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class CheckpointCorePermissions {
	private static final Decision ALLOW = new Decision(true, Component.empty());
	private static final ServerMutationGate MUTATION_GATE = new ServerMutationGate();

	private CheckpointCorePermissions() {
	}

	public static Decision evaluate(
			ServerPlayer player,
			ServerLevel level,
			BlockPos pos,
			boolean currentlyLit
	) {
		if (player.isSpectator()) {
			return denied("message.hardcore_checkpoints.checkpoint_core.spectator_denied");
		}

		var coordinator = CheckpointServerRuntime.coordinator().orElse(null);
		if (coordinator == null || !coordinator.state().featureEnabled()) {
			return currentlyLit
					? ALLOW
					: denied("message.hardcore_checkpoints.checkpoint_core.feature_disabled");
		}
		if (!coordinator.state().roster().contains(player.getUUID())) {
			return denied("message.hardcore_checkpoints.checkpoint_core.not_roster_member");
		}
		if (!MUTATION_GATE.allowsCheckpointCoreToggle(coordinator.state())) {
			return denied("message.hardcore_checkpoints.checkpoint_core.transaction_locked");
		}
		return ALLOW;
	}

	private static Decision denied(String translationKey) {
		return new Decision(false, Component.translatable(translationKey));
	}

	public record Decision(boolean allowed, Component denialReason) {
	}
}
