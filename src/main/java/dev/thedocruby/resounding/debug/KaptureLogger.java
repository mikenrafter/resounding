package dev.thedocruby.resounding.debug;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Formats the K-key "kapture" log lines for a focused ray's captured bounces: one header line,
 * one line per bounce, and one footer line. Pure formatting — no logging side effects, no
 * Minecraft-client imports beyond {@link Vec3d} (already a plain-data type used by
 * {@link CaptureBuffer.CapturedRay}).
 */
final class KaptureLogger {

	private KaptureLogger() {}

	/**
	 * @param rayIndex the env-eval ray index the bounces belong to (all elements of {@code bounces}
	 *     are expected to share this index; only used for header/footer text here)
	 * @param bounces non-empty list of captured bounces for {@code rayIndex}, in bounce order
	 * @return header line, one line per bounce, footer line — in that order
	 */
	static List<String> formatLines(int rayIndex, List<CaptureBuffer.CapturedRay> bounces) {
		List<String> lines = new ArrayList<>(bounces.size() + 2);
		int soundEventId = bounces.get(0).soundEventId();
		lines.add(String.format(Locale.ROOT,
				"Resounding KAPTURE ray #%d sound=%d bounces=%d ---",
				rayIndex, soundEventId, bounces.size()));
		for (CaptureBuffer.CapturedRay bounce : bounces) {
			lines.add(formatBounceLine(bounce));
		}
		lines.add(String.format(Locale.ROOT, "Resounding KAPTURE ray #%d --- end", rayIndex));
		return lines;
	}

	private static String formatBounceLine(CaptureBuffer.CapturedRay bounce) {
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT,
				"Resounding KAPTURE: ray #%d bounce #%d node=%d\u00b3 mode=%s start=%s end=%s "
						+ "material=%s Zprev=%.1f Z=%.1f R=%.3f T=%.3f power=%.1f polar=%.3f%s frustum=%.2f",
				bounce.rayIndex(),
				bounce.bounceIndex(),
				bounce.branchSize(),
				bounce.shapeMode() ? "SHAPE" : "VOXEL",
				formatPos(bounce.start()),
				formatPos(bounce.end()),
				bounce.materialLabel() == null ? "?" : bounce.materialLabel(),
				bounce.priorImpedance(),
				bounce.resolvedImpedance(),
				bounce.reflectivity(),
				bounce.transmission(),
				bounce.power(),
				bounce.polarAlignment(),
				// polarAlignment defaults to 1.0 for both "fully aligned" and "no polarity at
				// all" -- disambiguate explicitly instead of leaving both print identically.
				bounce.hasPolarity() ? "" : "/none",
				bounce.frustumSize()
		));
		String growth = growthTag(bounce);
		if (growth != null) {
			sb.append(" growth=").append(growth);
		}
		if (bounce.polarVector() != null) {
			sb.append(String.format(Locale.ROOT, " polarVec=%s", formatPos(bounce.polarVector())));
		}
		if (!Double.isNaN(bounce.commitWeight())) {
			sb.append(String.format(Locale.ROOT, " w=%.3f", bounce.commitWeight()));
		}
		sb.append(bounce.impedanceHomogeneous() ? " homogeneous" : " heterogeneous");
		if (bounce.blankReason() != null) {
			sb.append(" blank=").append(bounce.blankReason());
		}
		if (bounce.terminated()) {
			sb.append(" TERMINATED");
		}
		return sb.toString();
	}

	/** {@code null} when none of the growth-affecting flags fired this bounce. */
	private static String growthTag(CaptureBuffer.CapturedRay bounce) {
		if (bounce.peekReflect()) {
			return "peek";
		}
		if (bounce.freeRefraction()) {
			return "free";
		}
		if (bounce.growthDeferred()) {
			return "deferred";
		}
		return null;
	}

	/** Mirrors {@code Engine.formatPos}'s style (4-decimal, comma-separated, locale-independent). */
	private static String formatPos(Vec3d pos) {
		return String.format(Locale.ROOT, "%.4f,%.4f,%.4f", pos.x, pos.y, pos.z);
	}
}
