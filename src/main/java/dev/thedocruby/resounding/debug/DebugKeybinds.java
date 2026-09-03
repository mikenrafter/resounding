package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.Engine;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
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
	private static final KeyBinding CYCLE_FRUSTUM_RAY = KeyBindingHelper.registerKeyBinding(
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
				// From frustum (J) mode: first O switches to player-neighborhood; second O disables.
				if (layer.isEnabled() && layer.displayMode() == OctreeLayer.DisplayMode.BEAM_PATH) {
					layer.setDisplayMode(OctreeLayer.DisplayMode.NEIGHBORHOOD);
					layer.setEnabled(true);
					layer.update();
					if (client.player != null) {
						client.player.sendMessage(Text.literal(String.format(
								"Octree overlay: %d octants (7-cell neighborhood)",
								layer.octantCount()
						)).formatted(Formatting.AQUA), true);
					}
				} else {
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
			}
			while (CYCLE_FRUSTUM_RAY.wasPressed()) {
				var bounce = DebugRenderDispatcher.INSTANCE.bounceRays();
				var layer = DebugRenderDispatcher.INSTANCE.octree();
				boolean bounceOn = bounce.isEnabled();
				if (bounceOn) {
					int rayId = layer.cycleLiveFrustumRay();
					layer.update();
					if (client.player != null) {
						int count = layer.rayCount();
						String msg = count == 0
								? "Frustum octree: bounce rays have no segments (enable dRays / wait for a sound)"
								: String.format(
										"Frustum cast %d/%d (ray %d): %d LOD boxes",
										layer.selectedRayOrdinal() + 1,
										count,
										rayId,
										layer.octantCount()
								);
						client.player.sendMessage(Text.literal(msg).formatted(Formatting.AQUA), true);
					}
				} else {
					layer.showLookFrustum();
					layer.update();
					if (client.player != null) {
						client.player.sendMessage(Text.literal(String.format(
								"Frustum octree: look cast (%d boxes). Enable B to cycle bounce rays",
								layer.octantCount()
						)).formatted(Formatting.AQUA), true);
					}
				}
			}
			while (TOGGLE_CAPTURE.wasPressed()) {
				if (CaptureBuffer.INSTANCE.isCapturing()) {
					CaptureBuffer.INSTANCE.stopCapture();
				} else {
					CaptureBuffer.INSTANCE.startCapture(1);
				}
			}
		});
	}
}
