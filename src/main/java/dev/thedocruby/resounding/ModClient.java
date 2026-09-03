package dev.thedocruby.resounding;

import dev.thedocruby.resounding.config.BlueTapePack.ConfigManager;
import dev.thedocruby.resounding.debug.DebugKeybinds;
import dev.thedocruby.resounding.debug.DebugPicker;
import dev.thedocruby.resounding.debug.DebugRenderDispatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class ModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Engine.envType = EnvType.CLIENT;
		ConfigManager.registerAutoConfig();
		DebugRenderDispatcher.INSTANCE.register();
		DebugKeybinds.register();
		DebugPicker.register();
		ClientTickEvents.END_CLIENT_TICK.register(client -> OctreeManager.onClientTick());
		// TODO make more than debug
		// Cache.generate(Engine.LOGGER::info);
	}
}
