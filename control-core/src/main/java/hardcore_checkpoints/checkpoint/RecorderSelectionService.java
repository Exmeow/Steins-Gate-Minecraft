package hardcore_checkpoints.checkpoint;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class RecorderSelectionService {
	public <T> Set<T> selectNearestUnion(
			Map<UUID, PlayerPosition> players,
			Collection<RecorderCandidate<T>> candidates,
			double maximumDistance
	) {
		if (maximumDistance < 0) {
			throw new IllegalArgumentException("maximumDistance must not be negative");
		}
		double maximumDistanceSquared = maximumDistance * maximumDistance;
		Set<T> selected = new LinkedHashSet<>();
		for (PlayerPosition player : players.values()) {
			RecorderCandidate<T> nearest = null;
			double nearestDistance = Double.POSITIVE_INFINITY;
			for (RecorderCandidate<T> candidate : candidates) {
				if (!candidate.dimension().equals(player.dimension())) {
					continue;
				}
				double distance = candidate.distanceSquared(player);
				if (distance <= maximumDistanceSquared && distance < nearestDistance) {
					nearest = candidate;
					nearestDistance = distance;
				}
			}
			if (nearest == null) {
				return Set.of();
			}
			selected.add(nearest.value());
		}
		return Set.copyOf(selected);
	}

	public record PlayerPosition(String dimension, double x, double y, double z) {
		public PlayerPosition {
			Objects.requireNonNull(dimension, "dimension");
		}
	}

	public record RecorderCandidate<T>(T value, String dimension, double x, double y, double z) {
		public RecorderCandidate {
			Objects.requireNonNull(value, "value");
			Objects.requireNonNull(dimension, "dimension");
		}

		private double distanceSquared(PlayerPosition player) {
			double dx = x - player.x();
			double dy = y - player.y();
			double dz = z - player.z();
			return dx * dx + dy * dy + dz * dz;
		}
	}
}
