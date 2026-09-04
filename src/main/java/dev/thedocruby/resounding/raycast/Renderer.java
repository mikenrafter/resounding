package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.debug.BounceRayLayer;
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
			int rayIndex,
			@Nullable Material material,
			double reflectivity,
			double transmission,
			double power,
			double priorImpedance,
			int branchSize,
			@Nullable String materialLabel,
			boolean terminated,
			@Nullable BounceRayLayer.TerminationCause cause
	) {
		if (!pConfig.dRays) {
			return;
		}
		DebugRenderDispatcher.INSTANCE.bounceRays().addSoundBounceRay(
				start, end, color, bounceIndex, sourceID, rayIndex, material, reflectivity, transmission,
				power, branchSize, terminated, cause
		);
		if (CaptureBuffer.INSTANCE.isCapturing()) {
			int capturedColor = terminated ? (cause != null ? cause.color : BounceRayLayer.TERMINATED_COLOR) : color;
			CaptureBuffer.INSTANCE.offer(new CaptureBuffer.CapturedRay(
					start, end, capturedColor,
					sourceID, rayIndex, bounceIndex, material, reflectivity, transmission, power,
					priorImpedance, branchSize, materialLabel, terminated
			));
		}
	}

	public static void addTerminatorCross(Vec3d center, @Nullable BounceRayLayer.TerminationCause cause) {
		if (!pConfig.dRays) {
			return;
		}
		DebugRenderDispatcher.INSTANCE.bounceRays().addTerminatorCross(center, cause);
	}

	public static void addOcclusionRay(Vec3d start, Vec3d end, int color) {
		if (!pConfig.dRays) {
			return;
		}
		DebugRenderDispatcher.INSTANCE.occlusionRays().addOcclusionRay(start, end, color);
	}
}
