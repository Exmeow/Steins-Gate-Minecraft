package hardcore_checkpoints.fabric.command;

import net.minecraft.commands.arguments.UuidArgument;
import hardcore_checkpoints.HardcoreCheckpoints;
import hardcore_checkpoints.block.CheckpointCoreBlock;
import hardcore_checkpoints.block.entity.CheckpointCoreBlockEntity;
import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.WorldControlRuntime;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.control.repository.ControlStateRepository;
import hardcore_checkpoints.control.repository.WorldControlBootstrap;
import hardcore_checkpoints.fabric.network.FabricCheckpointNetworking;
import hardcore_checkpoints.fabric.platform.FabricWorldControlIntegration;
import hardcore_checkpoints.fabric.supervisor.FabricSupervisorBridge;
import hardcore_checkpoints.supervisor.protocol.SupervisorRestartRequest;
import hardcore_checkpoints.server.admin.AdministratorRepository;
import hardcore_checkpoints.server.state.CheckpointEvent;
import hardcore_checkpoints.server.state.CheckpointServerRuntime;
import hardcore_checkpoints.server.state.ServerMutationGate;
import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.command.BalmCommands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 专用服务器管理员命令入口。
 *
 * <p>命令只表达管理意图；名单和启停操作仍通过状态机、外部控制仓库与监督程序提交。
 * 任何需要切换激活周期的操作都会在持久化完成后请求受控重启。</p>
 */
public final class DedicatedServerCommands {
	private static final ResourceLocation ADMIN_PERMISSION = HardcoreCheckpoints.id("command.admin");
	private static final AtomicFileStore FILE_STORE = new AtomicFileStore();
	private static final AdministratorRepository ADMINISTRATORS = new AdministratorRepository(FILE_STORE);
	private static final ServerMutationGate MUTATION_GATE = new ServerMutationGate();

	private DedicatedServerCommands() {
	}

	/** 注册命令树，并通过 Balm 权限节点兼容服务端权限实现。 */
	public static void initialize() {
		BalmCommands.registerPermission(ADMIN_PERMISSION, 4);
		Balm.commands().register(dispatcher -> dispatcher.register(
				Commands.literal("checkpoint")
						.requires(DedicatedServerCommands::hasAdministrativePermission)
						.then(Commands.literal("status").executes(context -> status(context.getSource())))
						.then(Commands.literal("enable").executes(context -> enable(context.getSource())))
						.then(Commands.literal("disable").executes(context -> disable(context.getSource())))
						.then(Commands.literal("roster")
								.then(Commands.literal("list").executes(context -> listRoster(context.getSource())))
								.then(Commands.literal("add")
										.then(Commands.argument("player", UuidArgument.uuid())
												.executes(context -> addRoster(
														context.getSource(),
														UuidArgument.getUuid(context, "player")
												))))
								.then(Commands.literal("remove")
										.then(Commands.argument("player", UuidArgument.uuid())
												.executes(context -> removeRoster(
														context.getSource(),
														UuidArgument.getUuid(context, "player")
												)))))
						.then(Commands.literal("admin")
								.then(Commands.literal("list").executes(context -> listAdministrators(context.getSource())))
								.then(Commands.literal("add")
										.then(Commands.argument("player", UuidArgument.uuid())
												.executes(context -> addAdministrator(
														context.getSource(),
														UuidArgument.getUuid(context, "player")
												))))
								.then(Commands.literal("remove")
										.then(Commands.argument("player", UuidArgument.uuid())
												.executes(context -> removeAdministrator(
														context.getSource(),
														UuidArgument.getUuid(context, "player")
												)))))
		));
	}

	/**
	 * 控制台、原版四级权限和外部独立管理员名单均可执行管理命令。
	 * 外部名单读取失败时故障安全拒绝，不因存储异常放宽权限。
	 */
	private static boolean hasAdministrativePermission(CommandSourceStack source) {
		if (BalmCommands.requirePermission(ADMIN_PERMISSION).test(source)) {
			return true;
		}
		if (source.getEntity() == null) {
			return true;
		}
		return currentLayout().map(layout -> {
			try {
				return ADMINISTRATORS.contains(layout, source.getEntity().getUUID());
			} catch (IOException exception) {
				return false;
			}
		}).orElse(false);
	}

	private static int status(CommandSourceStack source) {
		var result = WorldControlRuntime.current().orElse(null);
		var coordinator = CheckpointServerRuntime.coordinator().orElse(null);
		if (result == null) {
			source.sendSuccess(() -> Component.literal("Hardcore Checkpoints: no control state is installed"), false);
			return 1;
		}
		String phase = coordinator == null ? result.status().name() : coordinator.state().phase().name();
		long rosterVersion = coordinator == null ? 0 : coordinator.state().roster().version();
		int rosterSize = coordinator == null ? 0 : coordinator.state().roster().members().size();
		source.sendSuccess(() -> Component.literal(
				"Hardcore Checkpoints: status=" + result.status()
						+ ", phase=" + phase
						+ ", rosterVersion=" + rosterVersion
						+ ", rosterSize=" + rosterSize
		), false);
		return 1;
	}

	/**
	 * 启用前必须完成监督握手、确认 Hardcore 世界，并初始化新的激活周期。
	 * 提交成功后不在当前进程继续运行，统一交给监督程序重启。
	 */
	private static int enable(CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		if (server.isSingleplayer()) {
			return fail(source, "Use the singleplayer save-list controls to enable checkpoints.");
		}
		if (!FabricSupervisorBridge.isConfigured()
				|| !FabricSupervisorBridge.isHealthy()
				|| !FabricSupervisorBridge.isResponsive()) {
			return fail(source, "A healthy Hardcore Checkpoints supervisor handshake is required before enabling.");
		}
		if (!server.getWorldData().isHardcore()) {
			return fail(source, "Checkpoint functionality can only be enabled for a hardcore world.");
		}
		try {
			Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			ControlStateRepository repository = new ControlStateRepository(FILE_STORE);
			WorldControlBootstrap.BootstrapResult inspected = new WorldControlBootstrap(repository).inspect(worldRoot);
			ControlRootLayout layout;
			if (inspected.status() == WorldControlBootstrap.BootstrapStatus.UNINITIALIZED_DISABLED) {
				layout = repository.initializeDisabled(worldRoot).layout();
			} else if (inspected.status() == WorldControlBootstrap.BootstrapStatus.FEATURE_DISABLED) {
				layout = inspected.optionalLayout().orElseGet(() -> {
					try {
						return repository.initializeDisabled(worldRoot).layout();
					} catch (IOException exception) {
						throw new IllegalStateException(exception);
					}
				});
			} else if (inspected.status() == WorldControlBootstrap.BootstrapStatus.CONTROL_READY) {
				return fail(source, "Checkpoint functionality is already enabled.");
			} else {
				return fail(source, "Control data is incomplete: " + String.join("; ", inspected.issues()));
			}
			repository.startNewActivation(layout);
			FabricWorldControlIntegration.reloadRunningControl(server);
			source.sendSuccess(() -> Component.literal(
					"Checkpoint functionality enabled with a new activation. The server will stop for restart."
			), true);
			FabricSupervisorBridge.requestControlledRestartAndStop(
					server,
					SupervisorRestartRequest.Reason.ENABLE_COMMITTED
			);
			return 1;
		} catch (IOException | RuntimeException exception) {
			HardcoreCheckpoints.LOGGER.error("Failed to enable checkpoint functionality", exception);
			return fail(source, "Enable failed: " + exception.getMessage());
		}
	}

	/**
	 * 禁用前先完整刷盘，再提交外部控制状态并熄灭已加载核心；失败时不发布半完成禁用。
	 */
	private static int disable(CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		if (server.isSingleplayer()) {
			return fail(source, "Exit the world and use the singleplayer save-list controls to disable checkpoints.");
		}
		if (!FabricSupervisorBridge.isConfigured()
				|| !FabricSupervisorBridge.isHealthy()
				|| !FabricSupervisorBridge.isResponsive()) {
			return fail(source, "A healthy Hardcore Checkpoints supervisor handshake is required before disabling.");
		}
		var coordinator = CheckpointServerRuntime.coordinator().orElse(null);
		var result = WorldControlRuntime.current().orElse(null);
		if (coordinator == null || result == null || result.optionalLayout().isEmpty()
				|| !coordinator.state().featureEnabled()) {
			return fail(source, "Checkpoint functionality is not enabled.");
		}
		if (!MUTATION_GATE.allowsDisable(coordinator.state())) {
			return fail(source, "Disable is not allowed during phase " + coordinator.state().phase());
		}
		try {
			if (!server.saveEverything(true, true, true)) {
				return fail(source, "World flush failed; disable state was not committed.");
			}
			new ControlStateRepository(FILE_STORE).disable(result.optionalLayout().orElseThrow());
			extinguishLoadedCores();
			FabricWorldControlIntegration.reloadRunningControl(server);
			source.sendSuccess(() -> Component.literal(
					"Checkpoint functionality disabled. The server will stop for restart."
			), true);
			FabricSupervisorBridge.requestControlledRestartAndStop(
					server,
					SupervisorRestartRequest.Reason.DISABLE_COMMITTED
			);
			return 1;
		} catch (IOException | RuntimeException exception) {
			HardcoreCheckpoints.LOGGER.error("Failed to disable checkpoint functionality", exception);
			return fail(source, "Disable failed: " + exception.getMessage());
		}
	}

	private static int listRoster(CommandSourceStack source) {
		var coordinator = CheckpointServerRuntime.coordinator().orElse(null);
		if (coordinator == null) {
			return fail(source, "No checkpoint roster is available.");
		}
		source.sendSuccess(() -> Component.literal(
				"Roster v" + coordinator.state().roster().version()
						+ ": " + coordinator.state().roster().members()
						+ "; pending=" + coordinator.state().roster().pendingAdditions()
		), false);
		return 1;
	}

	/** 名单修改携带当前版本，防止管理员基于陈旧状态覆盖并发更新。 */
	private static int addRoster(CommandSourceStack source, UUID playerId) {
		var coordinator = CheckpointServerRuntime.coordinator().orElse(null);
		if (coordinator == null) {
			return fail(source, "No checkpoint roster is available.");
		}
		try {
			var transition = coordinator.submit(new CheckpointEvent.AddRosterMember(
					playerId,
					coordinator.state().roster().version(),
					UUID.randomUUID(),
					false
			));
			if (!transition.accepted()) {
				return fail(source, "Roster add rejected: " + transition.code());
			}
			source.sendSuccess(() -> Component.literal("Added roster member " + playerId), true);
			return 1;
		} catch (IOException exception) {
			return fail(source, "Roster add failed: " + exception.getMessage());
		}
	}

	/**
	 * 移除成功后立即把在线目标送回 Configuration，使 Play 权限与新名单同步。
	 */
	private static int removeRoster(CommandSourceStack source, UUID playerId) {
		var coordinator = CheckpointServerRuntime.coordinator().orElse(null);
		if (coordinator == null) {
			return fail(source, "No checkpoint roster is available.");
		}
		try {
			var transition = coordinator.submit(new CheckpointEvent.RemoveRosterMember(
					playerId,
					coordinator.state().roster().version(),
					UUID.randomUUID()
			));
			if (!transition.accepted()) {
				return fail(source, "Roster remove rejected: " + transition.code());
			}
			FabricCheckpointNetworking.reconfigureRemovedPlayer(source.getServer(), playerId);
			source.sendSuccess(() -> Component.literal("Removed roster member " + playerId), true);
			return 1;
		} catch (IOException exception) {
			return fail(source, "Roster remove failed: " + exception.getMessage());
		}
	}

	private static int listAdministrators(CommandSourceStack source) {
		return currentLayout().map(layout -> {
			try {
				var members = ADMINISTRATORS.load(layout).members();
				source.sendSuccess(() -> Component.literal("Independent administrators: " + members), false);
				return 1;
			} catch (IOException exception) {
				return fail(source, "Administrator list failed: " + exception.getMessage());
			}
		}).orElseGet(() -> fail(source, "No initialized control root is available."));
	}

	private static int addAdministrator(CommandSourceStack source, UUID playerId) {
		return mutateAdministrator(source, playerId, true);
	}

	private static int removeAdministrator(CommandSourceStack source, UUID playerId) {
		return mutateAdministrator(source, playerId, false);
	}

	private static int mutateAdministrator(CommandSourceStack source, UUID playerId, boolean add) {
		return currentLayout().map(layout -> {
			try {
				if (add) {
					ADMINISTRATORS.add(layout, playerId);
				} else {
					ADMINISTRATORS.remove(layout, playerId);
				}
				source.sendSuccess(() -> Component.literal(
						(add ? "Added" : "Removed") + " independent administrator " + playerId
				), true);
				return 1;
			} catch (IOException exception) {
				return fail(source, "Administrator update failed: " + exception.getMessage());
			}
		}).orElseGet(() -> fail(source, "No initialized control root is available."));
	}

	private static java.util.Optional<ControlRootLayout> currentLayout() {
		return WorldControlRuntime.current().flatMap(WorldControlBootstrap.BootstrapResult::optionalLayout);
	}

	/** 禁用时同步更新所有已加载核心的方块状态，避免视觉状态继续显示已启用。 */
	private static void extinguishLoadedCores() {
		for (CheckpointCoreBlockEntity blockEntity : CheckpointCoreBlockEntity.loadedSnapshot()) {
			if (!(blockEntity.getLevel() instanceof ServerLevel level) || blockEntity.isRemoved()) {
				continue;
			}
			BlockPos pos = blockEntity.getBlockPos();
			BlockState state = level.getBlockState(pos);
			if (state.hasProperty(CheckpointCoreBlock.LIT) && state.getValue(CheckpointCoreBlock.LIT)) {
				level.setBlock(pos, state.setValue(CheckpointCoreBlock.LIT, false), Block.UPDATE_ALL);
			}
		}
	}

	private static int fail(CommandSourceStack source, String message) {
		source.sendFailure(Component.literal(message));
		return 0;
	}
}
