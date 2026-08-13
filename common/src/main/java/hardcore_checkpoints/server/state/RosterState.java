package hardcore_checkpoints.server.state;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record RosterState(long version, Set<UUID> members, Set<UUID> pendingAdditions) {
	public RosterState {
		if (version < 0) {
			throw new IllegalArgumentException("Roster version must not be negative");
		}
		members = Set.copyOf(members);
		pendingAdditions = Set.copyOf(pendingAdditions);
	}

	public static RosterState empty() {
		return new RosterState(0, Set.of(), Set.of());
	}

	public boolean contains(UUID playerId) {
		return members.contains(playerId);
	}

	public RosterState add(UUID playerId) {
		Objects.requireNonNull(playerId, "playerId");
		if (members.contains(playerId)) {
			return this;
		}
		Set<UUID> updated = new LinkedHashSet<>(members);
		updated.add(playerId);
		Set<UUID> pending = new LinkedHashSet<>(pendingAdditions);
		pending.remove(playerId);
		return new RosterState(version + 1, updated, pending);
	}

	public RosterState remove(UUID playerId) {
		Objects.requireNonNull(playerId, "playerId");
		if (!members.contains(playerId) && !pendingAdditions.contains(playerId)) {
			return this;
		}
		Set<UUID> updated = new LinkedHashSet<>(members);
		updated.remove(playerId);
		Set<UUID> pending = new LinkedHashSet<>(pendingAdditions);
		pending.remove(playerId);
		return new RosterState(version + 1, updated, pending);
	}

	public RosterState markPending(UUID playerId) {
		Objects.requireNonNull(playerId, "playerId");
		if (members.contains(playerId) || pendingAdditions.contains(playerId)) {
			return this;
		}
		Set<UUID> pending = new LinkedHashSet<>(pendingAdditions);
		pending.add(playerId);
		return new RosterState(version + 1, members, pending);
	}

	public RosterState commitPendingAdditions() {
		if (pendingAdditions.isEmpty()) {
			return this;
		}
		Set<UUID> updated = new LinkedHashSet<>(members);
		updated.addAll(pendingAdditions);
		return new RosterState(version + 1, updated, Set.of());
	}
}
