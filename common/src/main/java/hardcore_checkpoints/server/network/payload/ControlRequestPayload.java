package hardcore_checkpoints.server.network.payload;

import hardcore_checkpoints.HardcoreCheckpoints;
import hardcore_checkpoints.server.network.ControlAction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record ControlRequestPayload(
		UUID controlSessionId,
		UUID requestId,
		ControlAction action,
		UUID targetPlayerId,
		long expectedRosterVersion,
		UUID expectedEpochId
) implements CustomPacketPayload {
	public static final Type<ControlRequestPayload> TYPE = new Type<>(HardcoreCheckpoints.id("control_request"));
	public static final StreamCodec<FriendlyByteBuf, ControlRequestPayload> CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeUUID(payload.controlSessionId());
				buffer.writeUUID(payload.requestId());
				buffer.writeEnum(payload.action());
				buffer.writeBoolean(payload.targetPlayerId() != null);
				if (payload.targetPlayerId() != null) {
					buffer.writeUUID(payload.targetPlayerId());
				}
				buffer.writeVarLong(payload.expectedRosterVersion());
				buffer.writeUUID(payload.expectedEpochId());
			},
			buffer -> new ControlRequestPayload(
					buffer.readUUID(),
					buffer.readUUID(),
					buffer.readEnum(ControlAction.class),
					buffer.readBoolean() ? buffer.readUUID() : null,
					buffer.readVarLong(),
					buffer.readUUID()
			)
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
