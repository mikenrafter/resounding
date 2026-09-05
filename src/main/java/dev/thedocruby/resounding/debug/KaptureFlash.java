package dev.thedocruby.resounding.debug;

/**
 * Pure timing logic for the K-key "kapture" flash: the focused ray renders white 3x over 1.5s
 * before settling into steady (non-flashing) rendering.
 *
 * <p>No Minecraft imports on purpose — this is plain math, testable in isolation from render code.
 */
public final class KaptureFlash {

	/** Total flash duration in milliseconds. */
	private static final long FLASH_DURATION_MS = 1500L;
	/** Number of alternating white/transparent phases within {@link #FLASH_DURATION_MS}. */
	private static final int PHASE_COUNT = 7;

	private KaptureFlash() {}

	/** @return true while the flash sequence (0..1500ms) is still running for the given elapsed time. */
	public static boolean isFlashing(long elapsedMs) {
		return elapsedMs >= 0 && elapsedMs < FLASH_DURATION_MS;
	}

	/** @return which of the flash phases (0-6) elapsedMs falls into. */
	public static int phaseIndex(long elapsedMs) {
		return (int) ((elapsedMs * PHASE_COUNT) / FLASH_DURATION_MS);
	}

	/** @return whether the focused ray should render white (visible) at elapsedMs. */
	public static boolean isVisible(long elapsedMs) {
		return !isFlashing(elapsedMs) || phaseIndex(elapsedMs) % 2 == 0;
	}
}
