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

import java.util.Locale;

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
		boolean capturePeekable = CaptureBuffer.INSTANCE.isCapturing()
				|| !CaptureBuffer.INSTANCE.asCapturedList().isEmpty();
		boolean octreePeekable = DebugRenderDispatcher.INSTANCE.octree().isEnabled();
		if (!capturePeekable && !octreePeekable) {
			return;
		}

		Profiler profiler = client.getProfiler();
		profiler.push("resounding_debug_pick");
		try {
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
				for (Box box : DebugRenderDispatcher.INSTANCE.octree().boxes()) {
					BoxEdges.forEach(
							box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
							edge -> {
								Segment3.ClosestApproach approach = pick.closestApproachTo(edge);
								if (approach.distance() < PICK_THRESHOLD) {
									state.consider(
											approach.tSelf() * PICK_LENGTH,
											approach.distance(),
											new OctreeHit(box)
									);
								}
							}
					);
				}
			}

			if (state.hit != null) {
				client.player.sendMessage(Text.literal(state.hit.format()), true);
			}
		} finally {
			profiler.pop();
		}
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
			StringBuilder builder = new StringBuilder(96);
			builder.append(String.format(
					Locale.ROOT,
					"Ray b=%d id=%d R=%.2f T=%.2f pow=%.1f",
					ray.bounceIndex(),
					ray.soundEventId(),
					ray.reflectivity(),
					ray.transmission(),
					ray.power()
			));
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

	private record OctreeHit(Box box) implements PickHit {
		@Override
		public String format() {
			int size = (int) (box.maxX - box.minX);
			return String.format(
					Locale.ROOT,
					"Octant size=%d @ (%.0f,%.0f,%.0f)",
					size,
					box.minX,
					box.minY,
					box.minZ
			);
		}
	}
}
