package dev.thedocruby.resounding.debug;

import java.util.List;
import java.util.function.Consumer;

/**
 * Testable decision + logging logic for the K-key "kapture" action: a pure read of
 * {@link CaptureBuffer} for the currently focused ray.
 *
 * <p>Empty capture / no focused ray → dead key, no side effects, no flash.
 * Non-empty → log the ray's bounces to {@code logSink} and report that a flash should start.
 */
public final class KaptureAction {

	private KaptureAction() {}

	/**
	 * @return true if a flash should start (data was logged); false = dead key (no focus / no
	 *     bounces for the focused ray).
	 */
	public static boolean execute(
			int selectedRayIndex,
			List<CaptureBuffer.CapturedRay> bounces,
			Consumer<String> logSink
	) {
		if (selectedRayIndex < 0 || bounces == null || bounces.isEmpty()) {
			return false;
		}
		for (String line : KaptureLogger.formatLines(selectedRayIndex, bounces)) {
			logSink.accept(line);
		}
		return true;
	}
}
