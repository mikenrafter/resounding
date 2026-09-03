package dev.thedocruby.resounding;

import dev.thedocruby.resounding.config.BlueTapePack.ConfigManager;
import dev.thedocruby.resounding.debug.DebugKeybinds;
import dev.thedocruby.resounding.debug.DebugPicker;
import dev.thedocruby.resounding.debug.DebugRenderDispatcher;
import dev.thedocruby.resounding.network.ChunkGeneratedPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.util.math.ChunkPos;

public class ModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Engine.envType = EnvType.CLIENT;
		ConfigManager.registerAutoConfig();
		DebugRenderDispatcher.INSTANCE.register();
		DebugKeybinds.register();
		DebugPicker.register();
		ClientTickEvents.END_CLIENT_TICK.register(client -> OctreeManager.onClientTick());
		ClientPlayNetworking.registerGlobalReceiver(ChunkGeneratedPayload.ID, (payload, context) -> {
			ChunkPos pos = payload.pos();
			context.client().execute(() -> ChunkGenerationActivity.note(pos));
		});
	}
}
