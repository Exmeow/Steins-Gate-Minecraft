package hardcore_checkpoints.supervisor;

import hardcore_checkpoints.checkpoint.CheckpointPointer;
import hardcore_checkpoints.checkpoint.SnapshotVerifier;
import hardcore_checkpoints.control.ControlMetadata;
import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.repository.ControlStateRepository;
import hardcore_checkpoints.control.transfer.FileLockWorldActivityProbe;
import hardcore_checkpoints.recovery.DeathTransaction;
import hardcore_checkpoints.recovery.DeathTransactionRepository;
import hardcore_checkpoints.recovery.DeathTransactionStage;
import hardcore_checkpoints.recovery.OfflineRollbackService;
import hardcore_checkpoints.supervisor.protocol.SupervisorEnvironment;
import hardcore_checkpoints.supervisor.protocol.SupervisorHealthReport;
import hardcore_checkpoints.supervisor.protocol.SupervisorRollbackRequest;
import hardcore_checkpoints.supervisor.protocol.SupervisorRestartRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class SupervisorEngine implements AutoCloseable {
	private static final System.Logger LOGGER = System.getLogger(SupervisorEngine.class.getName());
	private static final Duration DEATH_STOP_TIMEOUT = Duration.ofSeconds(60);

	private final RunConfiguration configuration;
	private final UUID sessionId = UUID.randomUUID();
	private final String controlToken = randomToken();
	private final SupervisorState state = new SupervisorState(sessionId);
	private final SupervisorWorldLocator locator = new SupervisorWorldLocator();
	private final ControlStateRepository controls = new ControlStateRepository();
	private final DeathTransactionRepository deaths = new DeathTransactionRepository(controls);
	private final SnapshotVerifier verifier = new SnapshotVerifier(controls.fileStore());
	private final OfflineRollbackService rollbackService = new OfflineRollbackService(new FileLockWorldActivityProbe(), controls);
	private final ObsoleteCycleCleaner obsoleteCleaner = new ObsoleteCycleCleaner();
	private final AtomicReference<SupervisorRollbackRequest> rollbackRequest = new AtomicReference<>();
	private final AtomicReference<SupervisorRestartRequest> restartRequest = new AtomicReference<>();
	private final AtomicReference<SupervisorHealthReport> acceptedHealth = new AtomicReference<>();
	private final AtomicReference<Failure> fatalFailure = new AtomicReference<>();

	private volatile Process child;
	private volatile boolean closed;
	private SupervisorHttpServers httpServers;
	private SupervisorWorldLocator.LocatedWorld locatedWorld;

	SupervisorEngine(RunConfiguration configuration) {
		this.configuration = configuration;
	}

	void run() throws Exception {
		awaitHandoff();
		locatedWorld = locator.locate(configuration.worldRoot());
		httpServers = new SupervisorHttpServers(
				configuration,
				controlToken,
				state,
				this::acceptHealth,
				this::acceptRollback,
				this::acceptRestart
		);
		httpServers.start();
		LOGGER.log(System.Logger.Level.INFO, "Public status endpoint listening on port {0}", httpServers.statusPort());
		LOGGER.log(System.Logger.Level.INFO, "Internal control endpoint listening at {0}", httpServers.controlBaseUrl());

		Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("hardcore-checkpoints-supervisor-shutdown").unstarted(() -> {
			closed = true;
			try {
				ChildProcessTree.terminate(child, Duration.ZERO);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		}));

		if (!preparePendingRecovery()) {
			awaitFailure();
			return;
		}
		while (!closed) {
			launchChild();
			RunOutcome outcome = monitorChild();
			if (outcome == RunOutcome.RESTORE_AND_RESTART) {
				if (!restoreAfterStoppedChild()) {
					awaitFailure();
					return;
				}
				continue;
			}
			if (outcome == RunOutcome.RESTART) {
				locatedWorld = locator.locate(configuration.worldRoot());
				continue;
			}
			if (outcome == RunOutcome.FAILED) {
				awaitFailure();
			}
			return;
		}
	}

	private void awaitHandoff() throws Exception {
		long handoffPid = configuration.handoffPid();
		if (handoffPid == 0L) {
			return;
		}
		if (handoffPid == ProcessHandle.current().pid()) {
			throw new IllegalArgumentException("Supervisor handoff PID points to the supervisor process itself");
		}
		long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
		ProcessHandle previous = ProcessHandle.of(handoffPid).orElse(null);
		LOGGER.log(
				System.Logger.Level.INFO,
				"Waiting for Minecraft world lock handoff from pid={0} (alive={1})",
				handoffPid,
				previous != null && previous.isAlive()
		);
		FileLockWorldActivityProbe activityProbe = new FileLockWorldActivityProbe();
		while (activityProbe.isWorldRunning(configuration.worldRoot())) {
			if (System.nanoTime() >= deadline) {
				throw new IOException("Timed out waiting for the Minecraft world lock to be released");
			}
			TimeUnit.MILLISECONDS.sleep(100L);
		}
		LOGGER.log(System.Logger.Level.INFO, "Completed Minecraft world-lock handoff from pid={0}", handoffPid);
	}
	private boolean preparePendingRecovery() {
		try {
			DeathTransaction active = deaths.findActive(locatedWorld.layout()).orElse(null);
			if (active == null) {
				return true;
			}
			state.update(SupervisorPhase.RESTORING, 0.05, false);
			if (active.stage() == DeathTransactionStage.RECOVERY_FAILED) {
				if (!configuration.retryRecovery()) {
					state.fail(active.failureCode(), active.failureMessage(), false);
					return false;
				}
				rollbackService.retryStopped(locatedWorld.layout(), Instant.now());
			} else {
				rollbackService.restoreStopped(locatedWorld.layout(), Instant.now());
			}
			locatedWorld = locator.locate(configuration.worldRoot());
			state.update(SupervisorPhase.RESTORING, 1.0, false);
			return true;
		} catch (Exception exception) {
			fail("RECOVERY_PREPARE_FAILED", exception, false);
			return false;
		}
	}

	private void launchChild() throws IOException {
		restartRequest.set(null);
		rollbackRequest.set(null);
		acceptedHealth.set(null);
		fatalFailure.set(null);
		ProcessBuilder builder = new ProcessBuilder(configuration.childCommand());
		builder.directory(configuration.worldRoot().getParent().toFile());
		builder.inheritIO();
		builder.environment().put(SupervisorEnvironment.SESSION_ID, sessionId.toString());
		builder.environment().put(SupervisorEnvironment.CONTROL_URL, httpServers.controlBaseUrl());
		builder.environment().put(SupervisorEnvironment.CONTROL_TOKEN, controlToken);
		builder.environment().put(SupervisorEnvironment.STATUS_PORT, Integer.toString(httpServers.statusPort()));
		state.update(SupervisorPhase.BOOTING, 0.0, false);
		child = builder.start();
		state.update(SupervisorPhase.BOOTING, 0.1, true);
		LOGGER.log(System.Logger.Level.INFO, "Started Minecraft child process pid={0}", child.pid());
	}

	private RunOutcome monitorChild() throws InterruptedException {
		long healthDeadline = System.nanoTime() + configuration.healthTimeout().toNanos();
		boolean readyPublished = false;
		while (!closed) {
			Failure failure = fatalFailure.get();
			if (failure != null) {
				state.fail(failure.code(), failure.message(), child != null && child.isAlive());
				return RunOutcome.FAILED;
			}
			SupervisorRollbackRequest requestedRollback = rollbackRequest.get();
			if (requestedRollback != null) {
				state.update(SupervisorPhase.STOPPING, 0.0, child.isAlive());
				ChildProcessTree.terminate(child, DEATH_STOP_TIMEOUT);
				waitForFileHandles();
				return RunOutcome.RESTORE_AND_RESTART;
			}
			SupervisorRestartRequest requestedRestart = restartRequest.get();
			if (requestedRestart != null) {
				state.update(SupervisorPhase.STOPPING, 0.0, child.isAlive());
				if (child.waitFor(60, TimeUnit.SECONDS)) {
					waitForFileHandles();
					return RunOutcome.RESTART;
				}
				state.fail(
						"CONTROLLED_RESTART_TIMEOUT",
						"Minecraft did not stop after committed " + requestedRestart.reason(),
						true
				);
				return RunOutcome.FAILED;
			}
			if (!child.isAlive()) {
				DeathTransaction active = readActiveDeath();
				if (active != null && active.stage().shouldResumeAutomaticallyAfterRestart()) {
					state.update(SupervisorPhase.STOPPING, 1.0, false);
					waitForFileHandles();
					return RunOutcome.RESTORE_AND_RESTART;
				}
				int exitCode = child.exitValue();
				if (exitCode == 0) {
					state.update(SupervisorPhase.STOPPED, 1.0, false);
					return RunOutcome.STOPPED;
				}
				state.fail("MINECRAFT_EXITED", "Minecraft exited with code " + exitCode, false);
				return RunOutcome.FAILED;
			}
			if (acceptedHealth.get() != null && !readyPublished) {
				readyPublished = true;
				state.update(SupervisorPhase.READY_TO_CONNECT, 1.0, true);
				obsoleteCleaner.start(locatedWorld.layout());
			}
			if (!readyPublished && System.nanoTime() >= healthDeadline) {
				state.fail("HEALTH_TIMEOUT", "Minecraft did not complete the supervisor health handshake", true);
				return RunOutcome.FAILED;
			}
			TimeUnit.MILLISECONDS.sleep(100L);
		}
		return RunOutcome.STOPPED;
	}

	private boolean restoreAfterStoppedChild() {
		try {
			state.update(SupervisorPhase.RESTORING, 0.05, false);
			locatedWorld = locator.locate(configuration.worldRoot());
			rollbackService.restoreStopped(locatedWorld.layout(), Instant.now());
			locatedWorld = locator.locate(configuration.worldRoot());
			state.update(SupervisorPhase.RESTORING, 1.0, false);
			return true;
		} catch (Exception exception) {
			fail("RECOVERY_FAILED", exception, false);
			return false;
		}
	}

	private synchronized void acceptHealth(SupervisorHealthReport report) throws Exception {
		if (!sessionId.equals(report.sessionId())) {
			throw new IllegalArgumentException("Health report belongs to another supervisor session");
		}
		SupervisorHealthReport previous = acceptedHealth.get();
		if (previous != null) {
			if (previous.equals(report)) {
				return;
			}
			throw new IllegalStateException("A different health report was already accepted");
		}
		locatedWorld = locator.locate(configuration.worldRoot());
		ControlMetadata metadata = controls.readMetadata(locatedWorld.layout()).value();
		if (report.featureEnabled() != metadata.featureEnabled()
				|| !report.worldLineageId().equals(metadata.identity().worldLineageId())
				|| !report.worldInstanceId().equals(metadata.identity().worldInstanceId())
				|| !Objects.equals(report.activationId(), metadata.identity().activationId())) {
			throw new IllegalArgumentException("Health report world identity does not match active control metadata");
		}
		if (metadata.featureEnabled() && !report.gameplayFrozen()) {
			throw new IllegalArgumentException("Health report was sent before the default gameplay freeze was established");
		}
		boolean pointerExists = Files.isRegularFile(locatedWorld.layout().instanceRoot().resolve("checkpoint-pointer.json"));
		if (metadata.featureEnabled() && !pointerExists
				&& !java.util.Set.of("WAITING_FOR_ROSTER", "CREATING_INITIAL_CHECKPOINT", "INITIAL_CHECKPOINT_FAILED")
				.contains(report.serverPhase())) {
			throw new IllegalArgumentException("Enabled world without a checkpoint reported an invalid startup phase");
		}
		if (report.hasValidCheckpoint() != pointerExists) {
			throw new IllegalArgumentException("Health report checkpoint availability does not match disk state");
		}
		CheckpointPointer pointer = pointerExists ? publishedPointer(locatedWorld.layout(), metadata) : null;
		if (pointer != null && !report.checkpointId().equals(pointer.latestCheckpointId())) {
			throw new IllegalArgumentException("Health report checkpoint does not match the published latest checkpoint");
		}
		DeathTransaction active = deaths.findActive(locatedWorld.layout()).orElse(null);
		if (active != null) {
			if (active.stage() != DeathTransactionStage.RESTORING
					|| !active.checkpointId().equals(report.checkpointId())) {
				throw new IllegalStateException("Health report conflicts with active death recovery transaction");
			}
			rollbackService.confirmHealthy(
					locatedWorld.layout(),
					metadata.identity(),
					report.checkpointId(),
					Instant.now()
			);
		}
		acceptedHealth.set(report);
	}

	private synchronized void acceptRollback(SupervisorRollbackRequest request) throws Exception {
		if (!sessionId.equals(request.sessionId())) {
			throw new IllegalArgumentException("Rollback request belongs to another supervisor session");
		}
		DeathTransaction active = deaths.requireActive(locatedWorld.layout());
		if (active.stage() != DeathTransactionStage.ROLLBACK_PENDING || !request.matches(active)) {
			throw new IllegalStateException("Rollback request does not match the committed death transaction");
		}
		SupervisorRollbackRequest previous = rollbackRequest.get();
		if (previous != null && !previous.equals(request)) {
			throw new IllegalStateException("A different rollback request is already active");
		}
		rollbackRequest.set(request);
		state.update(SupervisorPhase.STOPPING, 0.0, child != null && child.isAlive());
	}

	private synchronized void acceptRestart(SupervisorRestartRequest request) throws Exception {
		if (!sessionId.equals(request.sessionId())) {
			throw new IllegalArgumentException("Restart request belongs to another supervisor session");
		}
		if (deaths.findActive(locatedWorld.layout()).isPresent()) {
			throw new IllegalStateException("Controlled restart cannot replace an active death recovery transaction");
		}
		locatedWorld = locator.locate(configuration.worldRoot());
		boolean expectedEnabled = request.reason() == SupervisorRestartRequest.Reason.ENABLE_COMMITTED;
		if (locatedWorld.metadata().featureEnabled() != expectedEnabled) {
			throw new IllegalStateException("Restart reason does not match committed feature state");
		}
		SupervisorRestartRequest previous = restartRequest.get();
		if (previous != null && !previous.equals(request)) {
			throw new IllegalStateException("A different controlled restart is already active");
		}
		restartRequest.set(request);
		state.update(SupervisorPhase.STOPPING, 0.0, child != null && child.isAlive());
	}

	private CheckpointPointer publishedPointer(ControlRootLayout layout, ControlMetadata metadata) throws IOException {
		if (!Files.isRegularFile(layout.instanceRoot().resolve("checkpoint-pointer.json"))) {
			throw new IOException("Enabled supervised world has no published checkpoint pointer");
		}
		return verifier.verifyPublished(layout, metadata.identity());
	}

	private DeathTransaction readActiveDeath() {
		try {
			locatedWorld = locator.locate(configuration.worldRoot());
			return deaths.findActive(locatedWorld.layout()).orElse(null);
		} catch (Exception exception) {
			fatalFailure.compareAndSet(null, new Failure("CONTROL_READ_FAILED", safeMessage(exception)));
			return null;
		}
	}

	private void fail(String code, Exception exception, boolean minecraftRunning) {
		String message = safeMessage(exception);
		state.fail(code, message, minecraftRunning);
		LOGGER.log(System.Logger.Level.ERROR, code + ": " + message, exception);
	}

	private void awaitFailure() throws InterruptedException {
		while (!closed) {
			TimeUnit.SECONDS.sleep(1L);
		}
	}

	private static void waitForFileHandles() throws InterruptedException {
		TimeUnit.MILLISECONDS.sleep(500L);
	}

	private static String randomToken() {
		byte[] token = new byte[32];
		new java.security.SecureRandom().nextBytes(token);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
	}

	private static String safeMessage(Exception exception) {
		return Objects.toString(exception.getMessage(), exception.getClass().getSimpleName());
	}

	@Override
	public void close() {
		closed = true;
		try {
			ChildProcessTree.terminate(child, Duration.ZERO);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
		if (httpServers != null) {
			httpServers.close();
		}
	}

	private enum RunOutcome {
		RESTORE_AND_RESTART,
		RESTART,
		STOPPED,
		FAILED
	}

	private record Failure(String code, String message) {
	}
}
