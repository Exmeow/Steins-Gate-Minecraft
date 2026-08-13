package hardcore_checkpoints.server.admin;

import java.util.Set;
import java.util.UUID;

public record AdministratorState(int schemaVersion, long version, Set<UUID> members) {
	public AdministratorState {
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("Unsupported administrator state schema: " + schemaVersion);
		}
		if (version < 0) {
			throw new IllegalArgumentException("Administrator version must not be negative");
		}
		members = Set.copyOf(members);
	}

	public static AdministratorState empty() {
		return new AdministratorState(1, 0, Set.of());
	}

	public AdministratorState add(UUID playerId) {
		if (members.contains(playerId)) {
			return this;
		}
		var updated = new java.util.LinkedHashSet<>(members);
		updated.add(playerId);
		return new AdministratorState(schemaVersion, version + 1, updated);
	}

	public AdministratorState remove(UUID playerId) {
		if (!members.contains(playerId)) {
			return this;
		}
		var updated = new java.util.LinkedHashSet<>(members);
		updated.remove(playerId);
		return new AdministratorState(schemaVersion, version + 1, updated);
	}
}
