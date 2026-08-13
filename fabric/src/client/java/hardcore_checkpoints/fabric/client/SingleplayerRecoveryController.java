package hardcore_checkpoints.fabric.client;

import hardcore_checkpoints.HardcoreCheckpoints;
import hardcore_checkpoints.control.ControlMetadata;
import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.WorldBinding;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.recovery.DeathTransactionRepository;
import hardcore_checkpoints.fabric.client.screen.SingleplayerRecoveryScreen;
import hardcore_checkpoints.fabric.client.state.CheckpointClientState;
import hardcore_checkpoints.fabric.recovery.FabricDeathRecoveryIntegration;
import hardcore_checkpoints.server.network.ControlStateSnapshot;
import hardcore_checkpoints.server.state.CheckpointPhase;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SingleplayerRecoveryController {
	private static final AtomicBoolean RECOVERING = new AtomicBoolean();
	private static final AtomicBoolean SCANNING = new AtomicBoolean();
	private static volatile boolean startupScanComplete;

	private SingleplayerRecoveryController() {
	}

	public static void initialize() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (RECOVERING.get() || client.getSingleplayerServer() != null) {
				return;
			}
			var pending = FabricDeathRecoveryIntegration.consumePendingSingleplayerRecovery();
			if (pending != null) {
				beginRecovery(client, pending);
				return;
			}
			if (!startupScanComplete && SCANNING.compareAndSet(false, true)) {
				scanStartupTransactions(client);
			}
		});
	}

	public static void onState(Minecraft minecraft, ControlStateSnapshot snapshot) {
		if (snapshot.phase() != CheckpointPhase.ROLLBACK_PENDING || !minecraft.hasSingleplayerServer()) {
			return;
		}
		beginPendingRecovery(minecraft);
	}

	public static void onDisconnected(Minecraft minecraft) {
		var pending = FabricDeathRecoveryIntegration.consumePendingSingleplayerRecovery();
		if (pending == null) {
			return;
		}
		// The descriptor is retained outside the network snapshot so a fast local shutdown cannot lose recovery ownership.
		minecraft.execute(() -> beginRecovery(minecraft, pending));
	}

	private static void scanStartupTransactions(Minecraft minecraft) {
		Path savesRoot = minecraft.getLevelSource().getBaseDir().toAbsolutePath().normalize();
		CompletableFuture.supplyAsync(() -> {
			try {
				return findPendingRecovery(savesRoot);
			} catch (IOException exception) {
				throw new java.util.concurrent.CompletionException(exception);
			}
		}).whenComplete((pending, failure) -> minecraft.execute(() -> {
			SCANNING.set(false);
			startupScanComplete = true;
			if (failure != null) {
				HardcoreCheckpoints.LOGGER.error("Failed to scan singleplayer death recovery transactions", failure);
				minecraft.setScreen(new GenericMessageScreen(Component.translatable(
						"screen.hardcore_checkpoints.recovery.failed",
						String.valueOf(failure.getCause() == null ? failure.getMessage() : failure.getCause().getMessage())
				)));
			} else if (pending != null) {
				beginRecovery(minecraft, pending);
			}
		}));
	}

	private static FabricDeathRecoveryIntegration.PendingSingleplayerRecovery findPendingRecovery(
			Path savesRoot
	) throws IOException {
		Path instancesRoot = savesRoot
				.resolve(ControlRootLayout.OWNERSHIP_DIRECTORY)
				.resolve("instances");
		if (!Files.isDirectory(instancesRoot, LinkOption.NOFOLLOW_LINKS)) {
			return null;
		}
		AtomicFileStore fileStore = new AtomicFileStore();
		DeathTransactionRepository deaths = new DeathTransactionRepository();
		var pending = new ArrayList<FabricDeathRecoveryIntegration.PendingSingleplayerRecovery>();
		try (var instances = Files.newDirectoryStream(instancesRoot)) {
			for (Path instanceRoot : instances) {
				if (!Files.isDirectory(instanceRoot, LinkOption.NOFOLLOW_LINKS)) {
					continue;
				}
				try {
					ControlMetadata metadata = fileStore.readRequired(
							instanceRoot.resolve("control.json"),
							ControlMetadata.class
					).value();
					WorldBinding binding = fileStore.readRequired(
							instanceRoot.resolve("binding.json"),
							WorldBinding.class
					).value();
					if (!binding.worldInstanceId().equals(metadata.identity().worldInstanceId())) {
						continue;
					}
					Path worldRoot = Path.of(binding.normalizedWorldPath()).toAbsolutePath().normalize();
					if (!savesRoot.equals(worldRoot.getParent())) {
						continue;
					}
					ControlRootLayout layout = ControlRootLayout.forWorld(
							worldRoot,
							metadata.identity().worldInstanceId()
					);
					if (!layout.instanceRoot().equals(instanceRoot.toAbsolutePath().normalize())) {
						continue;
					}
					var active = deaths.findActive(layout);
					if (active.isPresent()) {
						pending.add(new FabricDeathRecoveryIntegration.PendingSingleplayerRecovery(
								layout,
								worldRoot.getFileName().toString(),
								active.orElseThrow().transactionId()
						));
					}
				} catch (IOException | IllegalArgumentException exception) {
					HardcoreCheckpoints.LOGGER.warn(
							"Skipping unreadable singleplayer checkpoint instance {} during recovery scan",
							instanceRoot,
							exception
					);
				}
			}
		}
		if (pending.size() > 1) {
			throw new IOException("Multiple singleplayer worlds have active death recovery transactions");
		}
		return pending.isEmpty() ? null : pending.getFirst();
	}

	public static void reset() {
		RECOVERING.set(false);
	}

	private static void beginPendingRecovery(Minecraft minecraft) {
		var pending = FabricDeathRecoveryIntegration.consumePendingSingleplayerRecovery();
		if (pending != null) {
			beginRecovery(minecraft, pending);
		}
	}

	private static void beginRecovery(
			Minecraft minecraft,
			FabricDeathRecoveryIntegration.PendingSingleplayerRecovery pending
	) {
		if (!RECOVERING.compareAndSet(false, true)) {
			return;
		}
		beginRecovery(minecraft, pending.layout(), pending.levelId());
	}

	private static void beginRecovery(Minecraft minecraft, ControlRootLayout layout, String levelId) {
		try {
			CheckpointClientState.clear();
			minecraft.setScreen(new GenericMessageScreen(
					Component.translatable("screen.hardcore_checkpoints.recovery.stopping")
			));
			if (minecraft.level != null) {
				minecraft.level.disconnect();
			}
			minecraft.disconnect(new GenericMessageScreen(
					Component.translatable("screen.hardcore_checkpoints.recovery.stopping")
			));

			minecraft.setScreen(new SingleplayerRecoveryScreen(
					new TitleScreen(),
					layout,
					levelId,
					levelId,
					true
			));
		} catch (RuntimeException exception) {
			RECOVERING.set(false);
			HardcoreCheckpoints.LOGGER.error("Failed to start integrated checkpoint recovery", exception);
			minecraft.setScreen(new GenericMessageScreen(Component.translatable(
					"screen.hardcore_checkpoints.recovery.failed",
					String.valueOf(exception.getMessage())
			)));
		}
	}
}
