package dev.thedocruby.resounding.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;

/** S2C: server just finished generating this chunk (not a disk reload). */
public record ChunkGeneratedPayload(ChunkPos pos) implements CustomPayload {
    public static final Id<ChunkGeneratedPayload> ID =
            new Id<>(Identifier.of("resounding", "chunk_generated"));

    public static final PacketCodec<RegistryByteBuf, ChunkGeneratedPayload> CODEC =
            PacketCodec.of(ChunkGeneratedPayload::write, ChunkGeneratedPayload::new);

    private ChunkGeneratedPayload(RegistryByteBuf buf) {
        this(new ChunkPos(buf.readVarInt(), buf.readVarInt()));
    }

    private void write(RegistryByteBuf buf) {
        buf.writeVarInt(pos.x);
        buf.writeVarInt(pos.z);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
