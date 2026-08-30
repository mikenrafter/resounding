package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.material.Acoustics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CastMatchedImpedanceTest {

	private static final double STONE = 32_676_640.0;
	private static final double STONE_PERMEATION = 0.01;
	private static final double AIR = 412.0;

	private static double expectedTransmission(double reflectivity, double permeation, double distance) {
		return (1 - reflectivity) * Acoustics.permeationOverDistance(permeation, distance);
	}

	@Test
	void matchedStoneStoneBoundaryStillAppliesPermeationOverDistance() {
		double transmission = Cast.transmissionForBoundary(STONE, STONE, STONE_PERMEATION, 1.0);

		assertEquals(expectedTransmission(0, STONE_PERMEATION, 1.0), transmission, 1e-9);
		assertTrue(transmission < 1.0, "matched stone:stone must still attenuate via permeation");
	}

	@Test
	void mismatchedStoneAirBoundaryUsesReflectionAndPermeation() {
		double reflectivity = Acoustics.reflection(STONE, AIR);
		double transmission = Cast.transmissionForBoundary(STONE, AIR, STONE_PERMEATION, 1.0);

		assertEquals(expectedTransmission(reflectivity, STONE_PERMEATION, 1.0), transmission, 1e-9);
		assertTrue(transmission < 0.01, "stone→air must stay strongly attenuated");
	}

	@Test
	void matchedAirAirBoundaryTransmitsFully() {
		assertEquals(1.0, Cast.transmissionForBoundary(AIR, AIR, 1.0, 1.0), 1e-9);
	}
}
