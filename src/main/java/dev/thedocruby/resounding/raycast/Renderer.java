package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.debug.DebugRenderDispatcher;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.Vec3d;

import static dev.thedocruby.resounding.config.PrecomputedConfig.pConfig;

@Environment(EnvType.CLIENT)
public class Renderer {

	private Renderer() {}

	public static void addSoundBounceRay(Vec3d start, Vec3d end, int color) {
		if (!pConfig.dRays) {
			return;
		}
		DebugRenderDispatcher.INSTANCE.bounceRays().addSoundBounceRay(start, end, color);
	}

	public static void addOcclusionRay(Vec3d start, Vec3d end, int color) {
		if (!pConfig.dRays) {
			return;
		}
		DebugRenderDispatcher.INSTANCE.occlusionRays().addOcclusionRay(start, end, color);
	}
}
