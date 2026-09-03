package dev.thedocruby.resounding;

import dev.thedocruby.resounding.event.ChunkGenEvents;
import dev.thedocruby.resounding.network.ChunkGeneratedPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;

/**
 * Common init: payload codecs + chunk-generate sink → activity tracker (+ S2C for remote clients).
 */
public class Mod implements ModInitializer {
    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(ChunkGeneratedPayload.ID, ChunkGeneratedPayload.CODEC);

        ChunkGenEvents.CHUNK_GENERATE.register((world, chunk) -> {
            ChunkPos pos = chunk.getPos();
            ChunkGenerationActivity.note(pos);
            ChunkGeneratedPayload payload = new ChunkGeneratedPayload(pos);
            for (ServerPlayerEntity player : PlayerLookup.world(world)) {
                ServerPlayNetworking.send(player, payload);
            }
        });
    }
}
