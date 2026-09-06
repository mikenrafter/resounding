package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.Engine;
import dev.thedocruby.resounding.debug.math.BoxEdges;
import dev.thedocruby.resounding.debug.math.Segment3;
import dev.thedocruby.resounding.material.Material;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.profiler.Profiler;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

import static dev.thedocruby.resounding.config.PrecomputedConfig.pConfig;

@Environment(EnvType.CLIENT)
public final class DebugPicker {

	private static final double PICK_LENGTH = 64.0;
	private static final double PICK_THRESHOLD = 0.2;

	private DebugPicker() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!Engine.isActive || client.player == null) {
				return;
			}
			pick(client);
		});
	}

	private static void pick(MinecraftClient client) {
		boolean showSoundEffects = pConfig != null && pConfig.dRays;
		boolean showReadout = showSoundEffects || (pConfig != null && pConfig.eLog);
		boolean capturePeekable = CaptureBuffer.INSTANCE.isCapturing()
				&& DebugRenderDispatcher.INSTANCE.capture().isEnabled();
		boolean octreePeekable = DebugRenderDispatcher.INSTANCE.octree().isEnabled();
		boolean captureStatusVisible = CaptureBuffer.INSTANCE.isCapturing()
				|| CaptureBuffer.INSTANCE.size() > 0;
		if (!showReadout && !capturePeekable && !octreePeekable && !captureStatusVisible) {
			return;
		}

		Profiler profiler = client.getProfiler();
		profiler.push("resounding_debug_pick");
		try {
			String message = null;
			if (showReadout) {
				SoundEffectReadout.Snapshot snapshot = SoundEffectReadout.latest();
				if (snapshot != null) {
					message = SoundEffectReadout.format(snapshot);
				}
			}

			String captureStatus = formatCaptureStatus();
			if (captureStatus != null) {
				message = message == null || message.isBlank()
						? captureStatus
						: captureStatus + " | " + message;
			}

			Vec3d eye = client.player.getEyePos();
			Vec3d look = client.player.getRotationVec(1.0f);
			Vec3d end = eye.add(look.multiply(PICK_LENGTH));
			Segment3 pick = new Segment3(eye.x, eye.y, eye.z, end.x, end.y, end.z);
			PickState state = new PickState();

			if (capturePeekable) {
				for (CaptureBuffer.CapturedRay ray : CaptureBuffer.INSTANCE.asCapturedList()) {
					Segment3 segment = new Segment3(
							ray.start().x, ray.start().y, ray.start().z,
							ray.end().x, ray.end().y, ray.end().z
					);
					Segment3.ClosestApproach approach = pick.closestApproachTo(segment);
					if (approach.distance() < PICK_THRESHOLD) {
						state.consider(approach.tSelf() * PICK_LENGTH, approach.distance(), new RayHit(ray));
					}
				}
			}

			if (octreePeekable) {
				for (OctreeOverlay.OctantView octant : DebugRenderDispatcher.INSTANCE.octree().octants()) {
					Box box = octant.box();
					BoxEdges.forEach(
							box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
							edge -> {
								Segment3.ClosestApproach approach = pick.closestApproachTo(edge);
								if (approach.distance() < PICK_THRESHOLD) {
									state.consider(
											approach.tSelf() * PICK_LENGTH,
											approach.distance(),
											new OctreeHit(octant)
									);
								}
							}
					);
				}
			}

			if (state.hit != null) {
				String pickLine = state.hit.format();
				message = message == null || message.isBlank()
						? pickLine
						: message + " | " + pickLine;
			}

			if (message != null && !message.isBlank()) {
				client.player.sendMessage(Text.literal(message), true);
			}
		} finally {
			profiler.pop();
		}
	}

	/** Persistent action-bar capture/kapture readiness (armed vs frozen buffer + focus). */
	private static @Nullable String formatCaptureStatus() {
		boolean armed = CaptureBuffer.INSTANCE.isCapturing();
		int segs = CaptureBuffer.INSTANCE.size();
		if (!armed && segs == 0) {
			return null;
		}
		int focus = BounceRayLayer.activeRayIndex();
		String focusLabel = focus < 0 ? "focus=-" : "focus=#" + focus;
		if (armed) {
			return String.format(Locale.ROOT, "CAP ARMED segs=%d %s", segs, focusLabel);
		}
		int focusSegs = focus < 0 ? 0 : CaptureBuffer.INSTANCE.segmentsForRayIndex(focus).size();
		return String.format(
				Locale.ROOT,
				"CAP READY segs=%d focusSegs=%d %s",
				segs,
				focusSegs,
				focusLabel
		);
	}

	private static final class PickState {
		private PickHit hit;
		private double along = Double.MAX_VALUE;
		private double distance = Double.MAX_VALUE;

		private void consider(double along, double distance, PickHit candidate) {
			if (along < this.along || (along == this.along && distance < this.distance)) {
				this.along = along;
				this.distance = distance;
				this.hit = candidate;
			}
		}
	}

	private sealed interface PickHit permits RayHit, OctreeHit {
		String format();
	}

	private record RayHit(CaptureBuffer.CapturedRay ray) implements PickHit {
		@Override
		public String format() {
			StringBuilder builder = new StringBuilder(128);
			builder.append(String.format(
					Locale.ROOT,
					"Ray b=%d id=%d R=%.2f T=%.2f pow=%.1f node=%d³ %s%s",
					ray.bounceIndex(),
					ray.soundEventId(),
					ray.reflectivity(),
					ray.transmission(),
					ray.power(),
					ray.branchSize(),
					ray.materialLabel() == null ? "?" : ray.materialLabel(),
					ray.terminated() ? " END" : ""
			));
			builder.append(String.format(Locale.ROOT, " Zprev=%.2f", ray.priorImpedance()));
			Material material = ray.material();
			if (material != null) {
				builder.append(String.format(
						Locale.ROOT,
						" Z=%.2f P=%.2f S=%.2f",
						material.impedance(),
						material.permeation(),
						material.state()
				));
			}
			return builder.toString();
		}
	}

	private record OctreeHit(OctreeOverlay.OctantView octant) implements PickHit {
		@Override
		public String format() {
			String materialName = octant.label() != null && !octant.label().isBlank()
					? octant.label()
					: "?";
			StringBuilder builder = new StringBuilder(48);
			builder.append(String.format(
					Locale.ROOT,
					"%s %d³",
					materialName,
					octant.size()
			));
			Material material = octant.material();
			if (material != null) {
				builder.append(String.format(
						Locale.ROOT,
						" Z=%.0f P=%.2f",
						material.impedance(),
						material.permeation()
				));
			}
			return builder.toString();
		}
	}
}
