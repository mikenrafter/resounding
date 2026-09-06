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
			boolean shapeMode,
			/** Whether the boundary's descriptor actually had a polarity vector — disambiguates
			 *  {@code polarAlignment == 1.0} meaning "fully aligned" from "no polarity at all". */
			boolean hasPolarity,
			/** Growth was withheld this step (Task E double-cover / Task F envelopment guard). */
			boolean growthDeferred,
			/** Growth deferral resolved via a free/lossless parent-polarity graze (no bounce, no reverb hit). */
			boolean freeRefraction,
			/** Growth was withheld and this step reflected instead, because a look-ahead peek at
			 *  the next same-size octant found a real boundary growth would have skipped over. */
			boolean peekReflect
	) {}

	private final int maxSegments;
	private RingBuffer<CapturedRay> segments;
	private int remaining;
	/** When true, the next {@link #onSoundEvalStart} / {@link #offer} wipes prior segments. */
	private boolean clearBeforeNextRecord;
	private boolean truncationWarningPending;
	private boolean truncationWarningFired;
	private int version;

	CaptureBuffer(int maxSegments) {
		this.maxSegments = maxSegments;
		this.segments = new RingBuffer<>(maxSegments);
	}

	/**
	 * Arm capture for the next {@code events} sound evals. Does <em>not</em> clear existing
	 * segments immediately — the wipe is deferred to the next record
	 * ({@link #onSoundEvalStart} / first {@link #offer}) so re-arming cannot erase an unread
	 * capture before Kapture runs.
	 */
	public synchronized void startCapture(int events) {
		remaining = events;
		clearBeforeNextRecord = true;
		truncationWarningPending = false;
		truncationWarningFired = false;
		version++;
	}

	public synchronized void stopCapture() {
		remaining = 0;
		version++;
	}

	public synchronized boolean isCapturing() {
		return remaining != 0;
	}

	/** Clears prior segments once when an armed capture begins a sound eval (not on later evals). */
	public synchronized void onSoundEvalStart() {
		if (remaining != 0) {
			clearSegmentsIfNeeded();
		}
	}

	public synchronized void onSoundEvalEnd() {
		if (remaining > 0) {
			remaining--;
			version++;
		}
	}

	/**
	 * Must be synchronized: {@code Engine.evalEnv} raycasts in parallel, and every debug segment
	 * offer races into the same {@link RingBuffer}.
	 */
	public synchronized void offer(CapturedRay ray) {
		if (remaining == 0) {
			return;
		}
		clearSegmentsIfNeeded();
		if (segments.size() == maxSegments) {
			if (!truncationWarningFired) {
				truncationWarningPending = true;
				truncationWarningFired = true;
			}
		}
		segments.offer(ray);
		version++;
	}

	private void clearSegmentsIfNeeded() {
		if (!clearBeforeNextRecord) {
			return;
		}
		segments = new RingBuffer<>(maxSegments);
		clearBeforeNextRecord = false;
		truncationWarningPending = false;
		truncationWarningFired = false;
		version++;
	}

	public synchronized boolean consumeTruncationWarning() {
		if (truncationWarningPending) {
			truncationWarningPending = false;
			return true;
		}
		return false;
	}

	public synchronized List<CapturedRay> asCapturedList() {
		return segments.asList();
	}

	/** Distinct env-eval ray indexes present in the capture, in first-seen order. */
	public synchronized List<Integer> capturedRayIndexes() {
		Set<Integer> ordered = new LinkedHashSet<>();
		for (CapturedRay ray : segments.asList()) {
			ordered.add(ray.rayIndex());
		}
		return new ArrayList<>(ordered);
	}

	public synchronized List<CapturedRay> segmentsForRayIndex(int rayIndex) {
		List<CapturedRay> out = new ArrayList<>();
		for (CapturedRay ray : segments.asList()) {
			if (ray.rayIndex() == rayIndex) {
				out.add(ray);
			}
		}
		return out;
	}

	public synchronized int version() {
		return version;
	}

	/** Total segments currently held (for debug HUD / dead-key diagnostics). */
	public synchronized int size() {
		return segments.size();
	}
}
