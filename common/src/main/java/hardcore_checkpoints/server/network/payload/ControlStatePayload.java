package hardcore_checkpoints.server.network.payload;

import hardcore_checkpoints.HardcoreCheckpoints;
import hardcore_checkpoints.server.network.ControlStateSnapshot;
import hardcore_checkpoints.server.state.CheckpointPhase;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

public record ControlStatePayload(ControlStateSnapshot snapshot) implements CustomPacketPayload {
	public static final Type<ControlStatePayload> TYPE = new Type<>(HardcoreCheckpoints.id("control_state"));
	public static final StreamCodec<FriendlyByteBuf, ControlStatePayload> CODEC = StreamCodec.of(
			(buffer, payload) -> writeSnapshot(buffer, payload.snapshot()),
			buffer -> new ControlStatePayload(readSnapshot(buffer))
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	private static void writeSnapshot(FriendlyByteBuf buffer, ControlStateSnapshot snapshot) {
		buffer.writeUUID(snapshot.controlSessionId());
		buffer.writeEnum(snapshot.phase());
		buffer.writeUUID(snapshot.epochId());
		buffer.writeVarLong(snapshot.rosterVersion());
		buffer.writeBoolean(snapshot.rosterMember());
		buffer.writeBoolean(snapshot.administrator());
		buffer.writeBoolean(snapshot.ready());
		buffer.writeBoolean(snapshot.canEnterPlay());
		buffer.writeBoolean(snapshot.deathCountdownGate());
		buffer.writeVarLong(snapshot.countdownMillis());
		buffer.writeUtf(snapshot.statusMessage(), 512);
		buffer.writeVarInt(snapshot.roster().size());
		for (ControlStateSnapshot.RosterEntry entry : snapshot.roster()) {
			buffer.writeUUID(entry.playerId());
			buffer.writeUtf(entry.displayName(), 64);
			buffer.writeUtf(entry.connectionStatus(), 64);
			buffer.writeBoolean(entry.ready());
		}
		buffer.writeVarInt(snapshot.joinRequests().size());
		for (ControlStateSnapshot.RosterEntry entry : snapshot.joinRequests()) {
			buffer.writeUUID(entry.playerId());
			buffer.writeUtf(entry.displayName(), 64);
			buffer.writeUtf(entry.connectionStatus(), 64);
			buffer.writeBoolean(entry.ready());
		}
	}

	private static ControlStateSnapshot readSnapshot(FriendlyByteBuf buffer) {
		var sessionId = buffer.readUUID();
		CheckpointPhase phase = buffer.readEnum(CheckpointPhase.class);
		var epochId = buffer.readUUID();
		long rosterVersion = buffer.readVarLong();
		boolean rosterMember = buffer.readBoolean();
		boolean administrator = buffer.readBoolean();
		boolean ready = buffer.readBoolean();
		boolean canEnterPlay = buffer.readBoolean();
		boolean deathGate = buffer.readBoolean();
		long countdownMillis = buffer.readVarLong();
		String message = buffer.readUtf(512);
		int size = buffer.readVarInt();
		if (size < 0 || size > 1024) {
			throw new IllegalArgumentException("Invalid roster snapshot size: " + size);
		}
		List<ControlStateSnapshot.RosterEntry> roster = new ArrayList<>(size);
		for (int index = 0; index < size; index++) {
			roster.add(new ControlStateSnapshot.RosterEntry(
					buffer.readUUID(),
					buffer.readUtf(64),
					buffer.readUtf(64),
					buffer.readBoolean()
			));
		}
		int joinRequestSize = buffer.readVarInt();
		if (joinRequestSize < 0 || joinRequestSize > 1024) {
			throw new IllegalArgumentException("Invalid join request snapshot size: " + joinRequestSize);
		}
		List<ControlStateSnapshot.RosterEntry> joinRequests = new ArrayList<>(joinRequestSize);
		for (int index = 0; index < joinRequestSize; index++) {
			joinRequests.add(new ControlStateSnapshot.RosterEntry(
					buffer.readUUID(),
					buffer.readUtf(64),
					buffer.readUtf(64),
					buffer.readBoolean()
			));
		}
		return new ControlStateSnapshot(
				sessionId,
				phase,
				epochId,
				rosterVersion,
				rosterMember,
				administrator,
				ready,
				canEnterPlay,
				deathGate,
				countdownMillis,
				message,
				roster,
				joinRequests
		);
	}
}
