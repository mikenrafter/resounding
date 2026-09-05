package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.debug.math.RingBuffer;
import dev.thedocruby.resounding.material.Material;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Environment(EnvType.CLIENT)
public final class CaptureBuffer {

	private static final int MAX_CAPTURED_SEGMENTS = 16384;

	public static final CaptureBuffer INSTANCE = new CaptureBuffer(MAX_CAPTURED_SEGMENTS);

	public record CapturedRay(
			Vec3d start,
			Vec3d end,
			int color,
			int soundEventId,
			/** Fibonacci/env-eval ray index within the sound event. */
			int rayIndex,
			int bounceIndex,
			@Nullable Material material,
			double reflectivity,
			double transmission,
			double power,
			/** Impedance this bounce's reflectivity was computed against (the medium the ray was previously in). */
			double priorImpedance,
			/** Octree node size (in blocks) the boundary was resolved at; >1 means a coarse cached node. */
			int branchSize,
			@Nullable String materialLabel,
			boolean terminated,
			/** Impedance resolved at this boundary (the medium the ray is entering). */
			double resolvedImpedance,
			/** Alignment of the ray direction against the boundary's polar axis, in [-1, 1]. */
			double polarAlignment,
			/** Frustum/beam footprint size at this bounce, in blocks. */
			double frustumSize,
			/** Reason the bounce was left blank (e.g. "VACUUM"); null means NONE. */
			@Nullable String blankReason,
			/** Whether this bounce was resolved against exact block shape (true) or voxel occupancy (false). */
			boolean shapeMode
	) {}

	private final int maxSegments;
	private RingBuffer<CapturedRay> segments;
	private int remaining;
	private boolean truncationWarningPending;
	private boolean truncationWarningFired;
	private int version;

	CaptureBuffer(int maxSegments) {
		this.maxSegments = maxSegments;
		this.segments = new RingBuffer<>(maxSegments);
	}

	public void startCapture(int events) {
		remaining = events;
		segments = new RingBuffer<>(maxSegments);
		truncationWarningPending = false;
		truncationWarningFired = false;
		version++;
	}

	public void stopCapture() {
		remaining = 0;
		version++;
	}

	public boolean isCapturing() {
		return remaining != 0;
	}

	public void onSoundEvalStart() {
	}

	public void onSoundEvalEnd() {
		if (remaining > 0) {
			remaining--;
			version++;
		}
	}

	public void offer(CapturedRay ray) {
		if (!isCapturing()) {
			return;
		}
		if (segments.size() == maxSegments) {
			if (!truncationWarningFired) {
				truncationWarningPending = true;
				truncationWarningFired = true;
			}
		}
		segments.offer(ray);
		version++;
	}

	public boolean consumeTruncationWarning() {
		if (truncationWarningPending) {
			truncationWarningPending = false;
			return true;
		}
		return false;
	}

	public List<CapturedRay> asCapturedList() {
		return segments.asList();
	}

	/** Distinct env-eval ray indexes present in the capture, in first-seen order. */
	public List<Integer> capturedRayIndexes() {
		Set<Integer> ordered = new LinkedHashSet<>();
		for (CapturedRay ray : asCapturedList()) {
			ordered.add(ray.rayIndex());
		}
		return new ArrayList<>(ordered);
	}

	public List<CapturedRay> segmentsForRayIndex(int rayIndex) {
		List<CapturedRay> out = new ArrayList<>();
		for (CapturedRay ray : asCapturedList()) {
			if (ray.rayIndex() == rayIndex) {
				out.add(ray);
			}
		}
		return out;
	}

	public int version() {
		return version;
	}
}
