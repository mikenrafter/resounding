package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.Engine;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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
	private static final KeyBinding TOGGLE_FRUSTUM_OCTREE = KeyBindingHelper.registerKeyBinding(
			new KeyBinding(
					"key.resounding.debug.toggle_frustum_octree",
					InputUtil.Type.KEYSYM,
					GLFW.GLFW_KEY_J,
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
				layer.setDisplayMode(OctreeLayer.DisplayMode.NEIGHBORHOOD);
				layer.setEnabled(!layer.isEnabled());
				if (layer.isEnabled()) {
					layer.update();
					if (client.player != null) {
						client.player.sendMessage(Text.literal(String.format(
								"Octree overlay: %d octants (7-cell neighborhood)",
								layer.octantCount()
						)).formatted(Formatting.AQUA), true);
					}
				}
			}
			while (TOGGLE_FRUSTUM_OCTREE.wasPressed()) {
				var layer = DebugRenderDispatcher.INSTANCE.octree();
				boolean enabling = !layer.isEnabled()
						|| layer.displayMode() != OctreeLayer.DisplayMode.BEAM_PATH;
				layer.setDisplayMode(OctreeLayer.DisplayMode.BEAM_PATH);
				layer.setEnabled(enabling);
				if (layer.isEnabled()) {
					layer.update();
					if (client.player != null) {
						boolean fromCapture = !CaptureBuffer.INSTANCE.asCapturedList().isEmpty();
						client.player.sendMessage(Text.literal(String.format(
								"Frustum octree: %d boxes (%s)",
								layer.octantCount(),
								fromCapture ? "captured rays as beams" : "look vector fallback"
						)).formatted(Formatting.AQUA), true);
					}
				}
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
