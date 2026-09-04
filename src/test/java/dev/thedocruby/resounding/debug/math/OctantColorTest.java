package dev.thedocruby.resounding.debug.math;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OctantColorTest {

	@Test
	void paletteHasEightDistinctColors() {
		Set<Integer> colors = new HashSet<>();
		for (int index = 0; index < 8; index++) {
			colors.add(OctantColor.forNode(index & 1, (index >> 1) & 1, (index >> 2) & 1, 1));
		}
		assertEquals(8, colors.size());
	}

	@Test
	void faceAdjacentSameScaleNodesDiffer() {
		int left = OctantColor.forNode(0, 0, 0, 4);
		int right = OctantColor.forNode(4, 0, 0, 4);
		int up = OctantColor.forNode(0, 4, 0, 4);
		assertNotEquals(left, right);
		assertNotEquals(left, up);
		assertNotEquals(right, up);
	}

	@Test
	void sameNodeIsStable() {
		assertEquals(
				OctantColor.forNode(16, 32, 48, 8),
				OctantColor.forNode(16, 32, 48, 8)
		);
	}

	@Test
	void consecutiveSetsDiffer() {
		assertNotEquals(OctantColor.forSet(0), OctantColor.forSet(1));
		assertNotEquals(OctantColor.forSet(1), OctantColor.forSet(2));
		assertNotEquals(OctantColor.forSet(2), OctantColor.forSet(3));
	}

	@Test
	void withOpacityKeepsRgb() {
		int base = 0xFFE6194B;
		int faded = OctantColor.withOpacity(base, 0.2F);
		assertEquals(0xE6194B, faded & 0x00FFFFFF);
		assertEquals(51, (faded >>> 24) & 0xFF);
	}
}
