package hardcore_checkpoints.server.network.payload;

import hardcore_checkpoints.HardcoreCheckpoints;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ProtocolHelloPayload(
		int protocolVersion,
		long capabilities,
		String implementationVersion,
		String loaderId
) implements CustomPacketPayload {
	public static final Type<ProtocolHelloPayload> TYPE = new Type<>(HardcoreCheckpoints.id("protocol_hello"));
	public static final StreamCodec<FriendlyByteBuf, ProtocolHelloPayload> CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeVarInt(payload.protocolVersion());
				buffer.writeVarLong(payload.capabilities());
				buffer.writeUtf(payload.implementationVersion(), 128);
				buffer.writeUtf(payload.loaderId(), 64);
			},
			buffer -> new ProtocolHelloPayload(
					buffer.readVarInt(),
					buffer.readVarLong(),
					buffer.readUtf(128),
					buffer.readUtf(64)
			)
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
