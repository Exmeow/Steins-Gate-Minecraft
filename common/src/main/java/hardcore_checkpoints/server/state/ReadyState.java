package hardcore_checkpoints.server.state;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public record ReadyState(
		Set<UUID> readyMembers,
		Set<UUID> playConnectedMembers,
		Long stableSinceNanos,
		Long resumeDeadlineNanos
) {
	public ReadyState {
		readyMembers = Set.copyOf(readyMembers);
		playConnectedMembers = Set.copyOf(playConnectedMembers);
	}

	public static ReadyState empty() {
		return new ReadyState(Set.of(), Set.of(), null, null);
	}

	public ReadyState markPlayConnected(UUID playerId) {
		Set<UUID> connected = new LinkedHashSet<>(playConnectedMembers);
		connected.add(playerId);
		return new ReadyState(readyMembers, connected, stableSinceNanos, resumeDeadlineNanos);
	}

	public ReadyState disconnect(UUID playerId) {
		Set<UUID> connected = new LinkedHashSet<>(playConnectedMembers);
		connected.remove(playerId);
		Set<UUID> ready = new LinkedHashSet<>(readyMembers);
		ready.remove(playerId);
		return new ReadyState(ready, connected, null, null);
	}

	public ReadyState markReady(UUID playerId) {
		Set<UUID> ready = new LinkedHashSet<>(readyMembers);
		ready.add(playerId);
		return new ReadyState(ready, playConnectedMembers, stableSinceNanos, resumeDeadlineNanos);
	}

	public ReadyState withdraw(UUID playerId) {
		Set<UUID> ready = new LinkedHashSet<>(readyMembers);
		ready.remove(playerId);
		return new ReadyState(ready, playConnectedMembers, null, null);
	}

	public ReadyState retainMembers(Set<UUID> members) {
		Set<UUID> ready = new LinkedHashSet<>(readyMembers);
		ready.retainAll(members);
		Set<UUID> connected = new LinkedHashSet<>(playConnectedMembers);
		connected.retainAll(members);
		return new ReadyState(ready, connected, null, null);
	}
	public ReadyState synchronizePlayConnected(Set<UUID> connectedMembers) {
		Set<UUID> ready = new LinkedHashSet<>(readyMembers);
		ready.retainAll(connectedMembers);
		return new ReadyState(ready, connectedMembers, null, null);
	}

	public ReadyState autoReadyConnected(Set<UUID> members, UUID excludedPlayer) {
		Set<UUID> ready = new LinkedHashSet<>(playConnectedMembers);
		ready.retainAll(members);
		ready.remove(excludedPlayer);
		return new ReadyState(ready, playConnectedMembers, null, null);
	}

	public ReadyState withStability(long stableSinceNanos, Long resumeDeadlineNanos) {
		return new ReadyState(readyMembers, playConnectedMembers, stableSinceNanos, resumeDeadlineNanos);
	}

	public boolean allRosterMembersReady(RosterState roster) {
		return !roster.members().isEmpty() && readyMembers.containsAll(roster.members());
	}
}
