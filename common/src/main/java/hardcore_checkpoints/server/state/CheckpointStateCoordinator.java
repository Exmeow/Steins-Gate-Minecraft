package hardcore_checkpoints.server.state;

import hardcore_checkpoints.HardcoreCheckpoints;

import hardcore_checkpoints.control.ControlRootLayout;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class CheckpointStateCoordinator {
	private static final int MAX_REQUEST_HISTORY = 2048;

	private final Thread ownerThread;
	private final ControlRootLayout layout;
	private final CheckpointStateRepository repository;
	private final CheckpointEventReducer reducer;
	private final Map<UUID, CheckpointEventReducer.Transition> requestHistory = new LinkedHashMap<>();
	private CheckpointServerState state;

	public CheckpointStateCoordinator(
			ControlRootLayout layout,
			CheckpointStateRepository repository,
			CheckpointServerState initialState
	) {
		this.ownerThread = Thread.currentThread();
		this.layout = layout;
		this.repository = repository;
		this.reducer = new CheckpointEventReducer();
		this.state = initialState;
	}

	public CheckpointServerState state() {
		return state;
	}

	public CheckpointEventReducer.Transition submit(CheckpointEvent event) throws IOException {
		assertOwnerThread();
		Optional<UUID> requestId = requestId(event);
		if (requestId.isPresent()) {
			CheckpointEventReducer.Transition previous = requestHistory.get(requestId.get());
			if (previous != null) {
				HardcoreCheckpoints.LOGGER.debug("Reused checkpoint request result for requestId={}", requestId.get());
				return previous;
			}
		}
		CheckpointServerState previousState = state;
		CheckpointEventReducer.Transition transition = reducer.reduce(previousState, event);
		if (transition.accepted() && transition.state() != previousState) {
			state = transition.state();
			repository.save(layout, state);
			if (previousState.phase() != state.phase()) {
				HardcoreCheckpoints.LOGGER.info(
						"Checkpoint state transition: {} --{}--> {} (sequence={})",
						previousState.phase(),
						event.getClass().getSimpleName(),
						state.phase(),
						state.stateSequence()
				);
			} else {
				HardcoreCheckpoints.LOGGER.debug(
						"Checkpoint event {} accepted in phase {} (sequence={})",
						event.getClass().getSimpleName(),
						state.phase(),
						state.stateSequence()
				);
			}
		} else if (!transition.accepted()) {
			HardcoreCheckpoints.LOGGER.warn(
					"Checkpoint event {} rejected in phase {}: {}",
					event.getClass().getSimpleName(),
					previousState.phase(),
					transition.code()
			);
		}
		requestId.ifPresent(id -> remember(id, transition));
		return transition;
	}

	private void assertOwnerThread() {
		if (Thread.currentThread() != ownerThread) {
			throw new IllegalStateException("Checkpoint state mutations must run on the owning server thread");
		}
	}

	private void remember(UUID requestId, CheckpointEventReducer.Transition transition) {
		requestHistory.put(requestId, transition);
		while (requestHistory.size() > MAX_REQUEST_HISTORY) {
			UUID oldest = requestHistory.keySet().iterator().next();
			requestHistory.remove(oldest);
		}
	}

	private static Optional<UUID> requestId(CheckpointEvent event) {
		if (event instanceof CheckpointEvent.AddRosterMember add) {
			return Optional.of(add.requestId());
		}
		if (event instanceof CheckpointEvent.RemoveRosterMember remove) {
			return Optional.of(remove.requestId());
		}
		return Optional.empty();
	}
}
