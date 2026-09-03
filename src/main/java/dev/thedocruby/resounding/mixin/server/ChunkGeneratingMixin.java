package dev.thedocruby.resounding.mixin.server;

import dev.thedocruby.resounding.event.ChunkGenEvents;
import net.minecraft.world.chunk.AbstractChunkHolder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkGenerating;
import net.minecraft.world.chunk.ChunkGenerationContext;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.WrapperProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Yarn 1.21+build.7 does not name the {@code convertToFullChunk} supplyAsync lambda
 * ({@code method_60553} in intermediary). Fabric's own lifecycle mixin targets that same
 * synthetic; we mirror their GENERATE guard ({@code !(chunk instanceof WrapperProtoChunk)}).
 */
@Mixin(ChunkGenerating.class)
public abstract class ChunkGeneratingMixin {
    @Inject(method = "method_60553", at = @At("TAIL"))
    private static void resounding$onConvertToFullChunk(
            Chunk chunk,
            ChunkGenerationContext context,
            AbstractChunkHolder holder,
            CallbackInfoReturnable<Chunk> cir
    ) {
        if (chunk instanceof WrapperProtoChunk) {
            return;
        }
        Chunk result = cir.getReturnValue();
        if (result instanceof WorldChunk worldChunk) {
            ChunkGenEvents.CHUNK_GENERATE.invoker().onChunkGenerate(context.world(), worldChunk);
        }
    }
}
