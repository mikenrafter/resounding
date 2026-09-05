package dev.thedocruby.resounding.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Approved timing contract for the K-key kapture flash: white 3x over 1.5s in 7 phases of ~214ms
 * each (white, transparent, white, transparent, white, transparent, white), then steady (visible,
 * not flashing) forever after.
 */
class KaptureFlashTest {

	@Test
	void t0_isWhite() {
		assertTrue(KaptureFlash.isFlashing(0));
		assertEquals(0, KaptureFlash.phaseIndex(0));
		assertTrue(KaptureFlash.isVisible(0));
	}

	@Test
	void t107_stillFirstWhite() {
		assertEquals(0, KaptureFlash.phaseIndex(107));
		assertTrue(KaptureFlash.isVisible(107));
	}

	@Test
	void t215_firstTransparent() {
		assertEquals(1, KaptureFlash.phaseIndex(215));
		assertFalse(KaptureFlash.isVisible(215));
	}

	@Test
	void t750_middleTransparent() {
		assertEquals(3, KaptureFlash.phaseIndex(750));
		assertFalse(KaptureFlash.isVisible(750));
	}

	@Test
	void t1499_lastWhite() {
		assertEquals(6, KaptureFlash.phaseIndex(1499));
		assertTrue(KaptureFlash.isVisible(1499));
	}

	@Test
	void t1600_steadyNotFlashing() {
		assertFalse(KaptureFlash.isFlashing(1600));
		assertTrue(KaptureFlash.isVisible(1600));
	}

	@Test
	void boundaries() {
		assertEquals(0, KaptureFlash.phaseIndex(214));
		assertEquals(1, KaptureFlash.phaseIndex(215));
		assertFalse(KaptureFlash.isFlashing(1500));
	}
}
