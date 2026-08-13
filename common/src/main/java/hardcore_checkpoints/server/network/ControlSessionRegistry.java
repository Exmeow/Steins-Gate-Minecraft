package hardcore_checkpoints.server.network;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ControlSessionRegistry {
	private final Map<UUID, ControlSession> sessionsByPlayer = new HashMap<>();

	public Registration register(UUID playerId, boolean administrator, boolean rosterMember, long nowNanos) {
		ControlSession existing = sessionsByPlayer.get(playerId);
		if (existing != null && existing.stage() != ConnectionStage.CLOSED) {
			return new Registration(false, existing, "healthy_session_exists");
		}
		ControlSession session = ControlSession.create(playerId, administrator, rosterMember, nowNanos);
		sessionsByPlayer.put(playerId, session);
		return new Registration(true, session, "ok");
	}

	public Optional<ControlSession> find(UUID playerId) {
		return Optional.ofNullable(sessionsByPlayer.get(playerId));
	}
	public Map<UUID, ControlSession> snapshot() {
		return Map.copyOf(sessionsByPlayer);
	}

	public boolean validate(UUID playerId, UUID sessionId) {
		ControlSession session = sessionsByPlayer.get(playerId);
		return session != null && session.stage() != ConnectionStage.CLOSED && session.sessionId().equals(sessionId);
	}

	public ControlSession update(ControlSession session) {
		ControlSession current = sessionsByPlayer.get(session.playerId());
		if (current == null || !current.sessionId().equals(session.sessionId())) {
			throw new IllegalStateException("Cannot update a stale control session");
		}
		sessionsByPlayer.put(session.playerId(), session);
		return session;
	}

	public void close(UUID playerId, UUID sessionId) {
		ControlSession current = sessionsByPlayer.get(playerId);
		if (current != null && current.sessionId().equals(sessionId)) {
			sessionsByPlayer.remove(playerId);
		}
	}

	public void clear() {
		sessionsByPlayer.clear();
	}

	public record Registration(boolean accepted, ControlSession session, String code) {
	}
}
