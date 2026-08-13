package hardcore_checkpoints.server.network;

import hardcore_checkpoints.server.state.CheckpointPhase;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ControlStateSnapshot(
		UUID controlSessionId,
		CheckpointPhase phase,
		UUID epochId,
		long rosterVersion,
		boolean rosterMember,
		boolean administrator,
		boolean ready,
		boolean canEnterPlay,
		boolean deathCountdownGate,
		long countdownMillis,
		String statusMessage,
		List<RosterEntry> roster,
		List<RosterEntry> joinRequests
) {
	public ControlStateSnapshot {
		Objects.requireNonNull(controlSessionId, "controlSessionId");
		Objects.requireNonNull(phase, "phase");
		Objects.requireNonNull(epochId, "epochId");
		Objects.requireNonNull(statusMessage, "statusMessage");
		roster = List.copyOf(roster);
		joinRequests = List.copyOf(joinRequests);
	}

	public record RosterEntry(UUID playerId, String displayName, String connectionStatus, boolean ready) {
		public RosterEntry {
			Objects.requireNonNull(playerId, "playerId");
			Objects.requireNonNull(displayName, "displayName");
			Objects.requireNonNull(connectionStatus, "connectionStatus");
		}
	}
}
