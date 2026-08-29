package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.Engine;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public final class DebugKeybinds {

	private static final String CATEGORY = "key.categories.resounding.debug";

	private static final KeyBinding TOGGLE_BOUNCE_RAYS = KeyBindingHelper.registerKeyBinding(
			new KeyBinding(
					"key.resounding.debug.toggle_bounce_rays",
					InputUtil.Type.KEYSYM,
					GLFW.GLFW_KEY_B,
					CATEGORY
			)
	);
	private static final KeyBinding TOGGLE_OCCLUSION_RAYS = KeyBindingHelper.registerKeyBinding(
			new KeyBinding(
					"key.resounding.debug.toggle_occlusion_rays",
					InputUtil.Type.KEYSYM,
					GLFW.GLFW_KEY_N,
					CATEGORY
			)
	);
	private static final KeyBinding TOGGLE_OCTREE = KeyBindingHelper.registerKeyBinding(
			new KeyBinding(
					"key.resounding.debug.toggle_octree",
					InputUtil.Type.KEYSYM,
					GLFW.GLFW_KEY_O,
					CATEGORY
			)
	);
	private static final KeyBinding TOGGLE_CAPTURE = KeyBindingHelper.registerKeyBinding(
			new KeyBinding(
					"key.resounding.debug.toggle_capture",
					InputUtil.Type.KEYSYM,
					GLFW.GLFW_KEY_C,
					CATEGORY
			)
	);

	private DebugKeybinds() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!Engine.isActive) {
				return;
			}

			while (TOGGLE_BOUNCE_RAYS.wasPressed()) {
				var layer = DebugRenderDispatcher.INSTANCE.bounceRays();
				layer.setEnabled(!layer.isEnabled());
			}
			while (TOGGLE_OCCLUSION_RAYS.wasPressed()) {
				var layer = DebugRenderDispatcher.INSTANCE.occlusionRays();
				layer.setEnabled(!layer.isEnabled());
			}
			while (TOGGLE_OCTREE.wasPressed()) {
				var layer = DebugRenderDispatcher.INSTANCE.octree();
				layer.setEnabled(!layer.isEnabled());
			}
			while (TOGGLE_CAPTURE.wasPressed()) {
				// Default: capture exactly the next sound event.
				// An open-ended / held-key variant is a possible future enhancement.
				if (CaptureBuffer.INSTANCE.isCapturing()) {
					CaptureBuffer.INSTANCE.stopCapture();
				} else {
					CaptureBuffer.INSTANCE.startCapture(1);
				}
			}
		});
	}
}
