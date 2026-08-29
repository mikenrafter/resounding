package dev.thedocruby.resounding.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BounceRayLayerTest {

	@Test
	void lineWidthScalesWithPowerAndStepsDownPerBounce() {
		assertEquals(4.0F, BounceRayLayer.lineWidthFor(128.0, 0), 1e-6);
		assertEquals(2.0F, BounceRayLayer.lineWidthFor(64.0, 0), 1e-6);
		assertEquals(3.0F, BounceRayLayer.lineWidthFor(128.0, 1), 1e-6);
		assertEquals(1.0F, BounceRayLayer.lineWidthFor(32.0, 2), 1e-6);
		assertEquals(1.0F, BounceRayLayer.lineWidthFor(1.0, 10), 1e-6);
	}
}
