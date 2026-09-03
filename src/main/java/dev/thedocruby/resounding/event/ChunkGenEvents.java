package dev.thedocruby.resounding.event;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Modloader sinks for chunk generation. Fabric API 0.100.4+1.21 only exposes
 * {@code ServerChunkEvents.CHUNK_LOAD}/{@code UNLOAD}; {@code CHUNK_GENERATE} arrived later.
 * We fire the same semantic Fabric eventually used: convert-to-full on a non-wrapper proto.
 */
public final class ChunkGenEvents {
    private ChunkGenEvents() {}

    /**
     * Called when a newly generated chunk finishes upgrading to a full {@link WorldChunk}.
     * Disk reloads that wrap an existing chunk as {@code WrapperProtoChunk} do not fire this.
     */
    public static final Event<Generate> CHUNK_GENERATE = EventFactory.createArrayBacked(
            Generate.class,
            callbacks -> (world, chunk) -> {
                for (Generate callback : callbacks) {
                    callback.onChunkGenerate(world, chunk);
                }
            }
    );

    @FunctionalInterface
    public interface Generate {
        void onChunkGenerate(ServerWorld world, WorldChunk chunk);
    }
}
