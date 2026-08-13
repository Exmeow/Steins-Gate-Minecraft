package hardcore_checkpoints.fabric.mixin;

import hardcore_checkpoints.fabric.supervisor.FabricSupervisorConsoleProxy;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.server.dedicated.DedicatedServer$1")
public abstract class DedicatedServerConsoleMixin {
	@Redirect(
			method = "run",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/dedicated/DedicatedServer;isStopped()Z"
			)
	)
	private boolean hardcoreCheckpoints$keepConsoleProxyAliveAfterServerStop(DedicatedServer server) {
		return !FabricSupervisorConsoleProxy.isActive() && server.isStopped();
	}

	@Redirect(
			method = "run",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/dedicated/DedicatedServer;isRunning()Z"
			)
	)
	private boolean hardcoreCheckpoints$keepConsoleProxyRunning(DedicatedServer server) {
		return FabricSupervisorConsoleProxy.isActive() || server.isRunning();
	}

	@Redirect(
			method = "run",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/dedicated/DedicatedServer;createCommandSourceStack()Lnet/minecraft/commands/CommandSourceStack;"
			)
	)
	private CommandSourceStack hardcoreCheckpoints$skipStoppedServerCommandSource(DedicatedServer server) {
		return !FabricSupervisorConsoleProxy.isActive() && server.isRunning()
				? server.createCommandSourceStack()
				: null;
	}

	@Redirect(
			method = "run",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/dedicated/DedicatedServer;handleConsoleInput(Ljava/lang/String;Lnet/minecraft/commands/CommandSourceStack;)V"
			)
	)
	private void hardcoreCheckpoints$forwardConsoleInput(
			DedicatedServer server,
			String line,
			CommandSourceStack source
	) {
		if (!FabricSupervisorConsoleProxy.forward(line) && server.isRunning()) {
			server.handleConsoleInput(line, source);
		}
	}
}
