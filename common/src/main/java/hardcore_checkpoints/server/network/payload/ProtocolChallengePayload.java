package hardcore_checkpoints.server.network.payload;

import hardcore_checkpoints.HardcoreCheckpoints;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record ProtocolChallengePayload(
		int protocolVersion,
		long requiredCapabilities,
		boolean featureEnabled,
		String serverVersion,
		int statusPort,
		UUID supervisorSessionId
) implements CustomPacketPayload {
	public static final Type<ProtocolChallengePayload> TYPE = new Type<>(HardcoreCheckpoints.id("protocol_challenge"));
	public static final StreamCodec<FriendlyByteBuf, ProtocolChallengePayload> CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeVarInt(payload.protocolVersion());
				buffer.writeVarLong(payload.requiredCapabilities());
				buffer.writeBoolean(payload.featureEnabled());
				buffer.writeUtf(payload.serverVersion(), 128);
				buffer.writeVarInt(payload.statusPort());
				buffer.writeBoolean(payload.supervisorSessionId() != null);
				if (payload.supervisorSessionId() != null) {
					buffer.writeUUID(payload.supervisorSessionId());
				}
			},
			buffer -> {
				int protocolVersion = buffer.readVarInt();
				long capabilities = buffer.readVarLong();
				boolean featureEnabled = buffer.readBoolean();
				String serverVersion = buffer.readUtf(128);
				int statusPort = buffer.readVarInt();
				UUID supervisorSessionId = buffer.readBoolean() ? buffer.readUUID() : null;
				return new ProtocolChallengePayload(
						protocolVersion,
						capabilities,
						featureEnabled,
						serverVersion,
						statusPort,
						supervisorSessionId
				);
			}
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
