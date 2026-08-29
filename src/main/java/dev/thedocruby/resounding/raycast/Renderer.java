package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.debug.CaptureBuffer;
import dev.thedocruby.resounding.debug.DebugRenderDispatcher;
import dev.thedocruby.resounding.material.Material;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import static dev.thedocruby.resounding.config.PrecomputedConfig.pConfig;

@Environment(EnvType.CLIENT)
public class Renderer {

	private Renderer() {}

	public static void addSoundBounceRay(
			Vec3d start,
			Vec3d end,
			int color,
			int bounceIndex,
			int sourceID,
			@Nullable Material material,
			double reflectivity,
			double transmission,
			double power
	) {
		if (!pConfig.dRays) {
			return;
		}
		DebugRenderDispatcher.INSTANCE.bounceRays().addSoundBounceRay(
				start, end, color, bounceIndex, sourceID, material, reflectivity, transmission, power
		);
		if (CaptureBuffer.INSTANCE.isCapturing()) {
			CaptureBuffer.INSTANCE.offer(new CaptureBuffer.CapturedRay(
					start, end, color, sourceID, bounceIndex, material, reflectivity, transmission, power
			));
		}
	}

	public static void addOcclusionRay(Vec3d start, Vec3d end, int color) {
		if (!pConfig.dRays) {
			return;
		}
		DebugRenderDispatcher.INSTANCE.occlusionRays().addOcclusionRay(start, end, color);
	}
}
