package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.debug.math.RingBuffer;
import dev.thedocruby.resounding.material.Material;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@Environment(EnvType.CLIENT)
public final class CaptureBuffer {

	private static final int MAX_CAPTURED_SEGMENTS = 16384;

	public static final CaptureBuffer INSTANCE = new CaptureBuffer(MAX_CAPTURED_SEGMENTS);

	public record CapturedRay(
			Vec3d start,
			Vec3d end,
			int color,
			int soundEventId,
			int bounceIndex,
			@Nullable Material material,
			double reflectivity,
			double transmission,
			double power,
			/** Impedance this bounce's reflectivity was computed against (the medium the ray was previously in). */
			double priorImpedance,
			/** Octree node size (in blocks) the boundary was resolved at; >1 means a coarse cached node. */
			int branchSize,
			@Nullable String materialLabel
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

	public int version() {
		return version;
	}
}
