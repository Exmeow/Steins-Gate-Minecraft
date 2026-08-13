package hardcore_checkpoints.fabric.platform;

import hardcore_checkpoints.HardcoreCheckpoints;
import hardcore_checkpoints.checkpoint.SnapshotVerifier;
import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.WorldControlRuntime;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.control.repository.ControlStateRepository;
import hardcore_checkpoints.control.repository.WorldControlBootstrap;
import hardcore_checkpoints.fabric.checkpoint.FabricCheckpointCaptureIntegration;
import hardcore_checkpoints.fabric.network.FabricCheckpointNetworking;
import hardcore_checkpoints.fabric.singleplayer.SingleplayerActivationIntent;
import hardcore_checkpoints.server.state.CheckpointServerRuntime;
import hardcore_checkpoints.server.state.CheckpointStateCoordinator;
import hardcore_checkpoints.server.state.CheckpointStateRepository;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FabricWorldControlIntegration {
	private static final ControlStateRepository CONTROL_REPOSITORY = new ControlStateRepository();
	private static final WorldControlBootstrap BOOTSTRAP = new WorldControlBootstrap(CONTROL_REPOSITORY);

	private FabricWorldControlIntegration() {
	}

	public static void initialize() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			WorldControlRuntime.clear();
			CheckpointServerRuntime.clear();
			Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			boolean enableCreatedWorld = SingleplayerActivationIntent.consume();
			if (enableCreatedWorld && server.isSingleplayer() && server.getWorldData().isHardcore()) {
				try {
					WorldControlBootstrap.BootstrapResult beforeEnable = BOOTSTRAP.inspect(worldRoot);
					ControlRootLayout layout = beforeEnable.status() == WorldControlBootstrap.BootstrapStatus.UNINITIALIZED_DISABLED
							? CONTROL_REPOSITORY.initializeDisabled(worldRoot).layout()
							: beforeEnable.optionalLayout().orElseThrow(() ->
									new IOException("Created world control data is not eligible for activation"));
					CONTROL_REPOSITORY.startNewActivation(layout);
				} catch (IOException exception) {
					throw new IllegalStateException("Failed to enable checkpoints for the created world", exception);
				}
			}
			WorldControlBootstrap.BootstrapResult result = BOOTSTRAP.inspect(worldRoot);
			if (result.status() == WorldControlBootstrap.BootstrapStatus.INCOMPLETE_CONTROL_DATA) {
				String issues = String.join("; ", result.issues());
				HardcoreCheckpoints.LOGGER.error(
						"Refusing to start world {} because checkpoint control data is incomplete: {}",
						worldRoot,
						issues
				);
				throw new IllegalStateException("Incomplete Hardcore Checkpoints control data: " + issues);
			}

			WorldControlRuntime.install(result);
			if (result.optionalLayout().isPresent() && result.optionalMetadata().isPresent()) {
				var layout = result.optionalLayout().orElseThrow();
				var metadata = result.optionalMetadata().orElseThrow();
				try {
					AtomicFileStore fileStore = new AtomicFileStore();
					CheckpointStateRepository stateRepository = new CheckpointStateRepository(fileStore);
					boolean hasValidCheckpoint = Files.isRegularFile(layout.instanceRoot().resolve("checkpoint-pointer.json"));
					if (hasValidCheckpoint) {
						new SnapshotVerifier(fileStore).verifyPublished(layout, metadata.identity());
					}
					var initialState = stateRepository.loadOrCreate(layout, metadata.featureEnabled(), hasValidCheckpoint);
					CheckpointServerRuntime.install(new CheckpointStateCoordinator(layout, stateRepository, initialState));
					HardcoreCheckpoints.LOGGER.info(
							"Loaded checkpoint server state (phase={}, sequence={}, rosterMembers={}, validCheckpoint={})",
							initialState.phase(),
							initialState.stateSequence(),
							initialState.roster().members().size(),
							initialState.hasValidCheckpoint()
					);
				} catch (IOException exception) {
					throw new IllegalStateException("Failed to load Hardcore Checkpoints server state", exception);
				}
			}

			result.optionalMetadata().ifPresentOrElse(
					metadata -> HardcoreCheckpoints.LOGGER.info(
							"Checkpoint control status {} for world instance {} (lineage {}, activation {})",
							result.status(),
							metadata.identity().worldInstanceId(),
							metadata.identity().worldLineageId(),
							metadata.identity().activationId()
					),
					() -> HardcoreCheckpoints.LOGGER.info(
							"Checkpoint control status {} for uninitialized world {}",
							result.status(),
							worldRoot
					)
			);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			FabricCheckpointCaptureIntegration.clear();
			FabricGameplayFreezeController.releaseForShutdown(server);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			FabricCheckpointNetworking.clear();
			FabricGameplayFreezeController.clear();
			CheckpointServerRuntime.clear();
			WorldControlRuntime.clear();
		});
	}

	public static WorldControlBootstrap.BootstrapResult reloadRunningControl(MinecraftServer server) throws IOException {
		Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		WorldControlBootstrap.BootstrapResult result = BOOTSTRAP.inspect(worldRoot);
		if (result.status() == WorldControlBootstrap.BootstrapStatus.INCOMPLETE_CONTROL_DATA) {
			throw new IOException("Incomplete Hardcore Checkpoints control data: " + String.join("; ", result.issues()));
		}
		WorldControlRuntime.install(result);
		CheckpointServerRuntime.clear();
		if (result.optionalLayout().isPresent() && result.optionalMetadata().isPresent()) {
			var layout = result.optionalLayout().orElseThrow();
			var metadata = result.optionalMetadata().orElseThrow();
			AtomicFileStore fileStore = new AtomicFileStore();
			boolean hasValidCheckpoint = Files.isRegularFile(layout.instanceRoot().resolve("checkpoint-pointer.json"));
			if (hasValidCheckpoint) {
				new SnapshotVerifier(fileStore).verifyPublished(layout, metadata.identity());
			}
			CheckpointStateRepository stateRepository = new CheckpointStateRepository(fileStore);
			var state = stateRepository.loadOrCreate(layout, metadata.featureEnabled(), hasValidCheckpoint);
			CheckpointServerRuntime.install(new CheckpointStateCoordinator(layout, stateRepository, state));
			HardcoreCheckpoints.LOGGER.info(
					"Reloaded checkpoint server state (phase={}, sequence={}, rosterMembers={}, validCheckpoint={})",
					state.phase(),
					state.stateSequence(),
					state.roster().members().size(),
					state.hasValidCheckpoint()
			);
		}
		FabricCheckpointCaptureIntegration.clear();
		return result;
	}
}
