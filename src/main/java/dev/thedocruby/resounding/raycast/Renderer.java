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

	/**
	 * @param cast the {@link Cast} this bounce segment was resolved against — its {@code last*}
	 *     fields are snapshotted here (both for the live overlay and, when capturing, for
	 *     {@link CaptureBuffer}) rather than exploding this method's parameter list further.
	 */
	public static void addSoundBounceRay(
			Cast cast,
			Vec3d start,
			Vec3d end,
			int bounceIndex,
			int sourceID,
			int rayIndex,
			double power,
			boolean terminated,
			@Nullable BounceRayLayer.TerminationCause cause
	) {
		if (!pConfig.dRays) {
			return;
		}
		int color = cast.lastOctantColor;
		@Nullable Material material = cast.lastMaterial;
		double reflectivity = cast.lastBoundaryResolved ? cast.lastReflectivity : 0.0;
		double transmission = cast.lastBoundaryResolved ? cast.lastTransmission : 0.0;
		double priorImpedance = cast.lastPriorImpedance;
		int branchSize = cast.lastBranchSize;
		@Nullable String materialLabel = cast.lastMaterialLabel;

		DebugRenderDispatcher.INSTANCE.bounceRays().addSoundBounceRay(
				start, end, color, bounceIndex, sourceID, rayIndex, material, reflectivity, transmission,
				power, branchSize, terminated, cause
		);
		if (CaptureBuffer.INSTANCE.isCapturing()) {
			int capturedColor = terminated ? (cause != null ? cause.color : BounceRayLayer.TERMINATED_COLOR) : color;
			double resolvedImpedance = cast.lastResolvedImpedance;
			double polarAlignment = cast.lastPolarAlignment;
			double frustumSize = cast.frustumSize;
			@Nullable String blankReason = cast.lastBlankReason == Cast.BlankReason.NONE
					? null
					: cast.lastBlankReason.name();
			boolean shapeMode = cast.lastShapeMode;
			CaptureBuffer.INSTANCE.offer(new CaptureBuffer.CapturedRay(
					start, end, capturedColor,
					sourceID, rayIndex, bounceIndex, material, reflectivity, transmission, power,
					priorImpedance, branchSize, materialLabel, terminated,
					resolvedImpedance, polarAlignment, frustumSize, blankReason, shapeMode,
					cast.lastHasPolarity, cast.lastGrowthDeferred, cast.lastFreeRefraction, cast.lastPeekReflect,
					cast.lastPolarVector, cast.lastCommitWeight, cast.lastImpedanceHomogeneous
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
