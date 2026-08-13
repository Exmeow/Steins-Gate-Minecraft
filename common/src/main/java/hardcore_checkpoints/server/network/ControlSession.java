package hardcore_checkpoints.server.network;

import java.util.Objects;
import java.util.UUID;

public record ControlSession(
		UUID sessionId,
		UUID playerId,
		ConnectionStage stage,
		boolean administrator,
		boolean rosterMember,
		long connectedAtNanos,
		long lastSeenNanos
) {
	public ControlSession {
		Objects.requireNonNull(sessionId, "sessionId");
		Objects.requireNonNull(playerId, "playerId");
		Objects.requireNonNull(stage, "stage");
	}

	public static ControlSession create(UUID playerId, boolean administrator, boolean rosterMember, long nowNanos) {
		return new ControlSession(
				UUID.randomUUID(),
				playerId,
				ConnectionStage.CONFIGURING,
				administrator,
				rosterMember,
				nowNanos,
				nowNanos
		);
	}

	public ControlSession withStage(ConnectionStage newStage, long nowNanos) {
		return new ControlSession(sessionId, playerId, newStage, administrator, rosterMember, connectedAtNanos, nowNanos);
	}

	public ControlSession withPermissions(boolean admin, boolean member, long nowNanos) {
		return new ControlSession(sessionId, playerId, stage, admin, member, connectedAtNanos, nowNanos);
	}
}
