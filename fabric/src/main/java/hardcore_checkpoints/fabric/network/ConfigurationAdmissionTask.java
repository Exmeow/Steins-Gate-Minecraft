package hardcore_checkpoints.fabric.network;

import hardcore_checkpoints.server.network.ControlProtocol;
import hardcore_checkpoints.fabric.supervisor.FabricSupervisorBridge;
import hardcore_checkpoints.server.network.payload.ProtocolChallengePayload;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ConfigurationTask;

import java.util.function.Consumer;

public final class ConfigurationAdmissionTask implements ConfigurationTask {
	public static final Type TYPE = new Type("hardcore_checkpoints:control_admission");

	private final boolean featureEnabled;
	private final String serverVersion;

	public ConfigurationAdmissionTask(boolean featureEnabled, String serverVersion) {
		this.featureEnabled = featureEnabled;
		this.serverVersion = serverVersion;
	}

	@Override
	public void start(Consumer<Packet<?>> packetSender) {
		packetSender.accept(ServerConfigurationNetworking.createS2CPacket(new ProtocolChallengePayload(
				ControlProtocol.VERSION,
				ControlProtocol.REQUIRED_CAPABILITIES,
				featureEnabled,
				serverVersion,
				FabricSupervisorBridge.statusPort(),
				FabricSupervisorBridge.sessionId()
		)));
	}

	@Override
	public Type type() {
		return TYPE;
	}
}
