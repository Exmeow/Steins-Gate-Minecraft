package hardcore_checkpoints.server.state;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record EpochState(long number, UUID epochId, long rosterVersion, ReadyState ready) {
	public EpochState {
		if (number < 0) {
			throw new IllegalArgumentException("Epoch number must not be negative");
		}
		Objects.requireNonNull(epochId, "epochId");
		Objects.requireNonNull(ready, "ready");
	}

	public static EpochState initial(long rosterVersion) {
		return new EpochState(0, UUID.randomUUID(), rosterVersion, ReadyState.empty());
	}

	public EpochState next(RosterState roster, Set<UUID> autoReady, Set<UUID> connected) {
		ReadyState readyState = new ReadyState(autoReady, connected, null, null).retainMembers(roster.members());
		return new EpochState(number + 1, UUID.randomUUID(), roster.version(), readyState);
	}

	public EpochState withRoster(RosterState roster) {
		return new EpochState(number, epochId, roster.version(), ready.retainMembers(roster.members()));
	}

	public EpochState clearReady() {
		return new EpochState(number, epochId, rosterVersion, ReadyState.empty());
	}

	public EpochState withReady(ReadyState readyState) {
		return new EpochState(number, epochId, rosterVersion, readyState);
	}
}
