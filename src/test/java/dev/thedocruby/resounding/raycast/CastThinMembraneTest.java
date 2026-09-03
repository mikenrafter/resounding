package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.material.Acoustics;
import dev.thedocruby.resounding.material.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CastThinMembraneTest {

	private static final double AIR = 415.0;
	private static final double STONE = 1.0e7;
	private static final Material AIR_MAT = new Material(AIR, 1.0, 0.5, 1.0);
	private static final Material STONE_MAT = new Material(STONE, 0.01, 1.0, 0.8);

	@Test
	void thinMembraneExitSkipsSecondMismatchAtStoneAirBoundary() {
		Cast cast = new Cast(null, null, null);
		cast.impeded = STONE;
		cast.impededSet = true;
		cast.enteredFrom = AIR;
		cast.solidTransitDistance = 1.0;

		assertTrue(cast.isThinMembraneExit(AIR));
	}

	@Test
	void thickSolidTransitStillReflectsAtExit() {
		Cast cast = new Cast(null, null, null);
		cast.impeded = STONE;
		cast.impededSet = true;
		cast.enteredFrom = AIR;
		cast.solidTransitDistance = 2.0;

		assertFalse(cast.isThinMembraneExit(AIR));
	}

	@Test
	void entryIntoStoneStillUsesFullMismatch() {
		Cast cast = new Cast(null, null, null);
		cast.impeded = AIR;
		cast.impededSet = true;

		assertFalse(cast.isThinMembraneExit(STONE));
		double reflectivity = Acoustics.reflection(AIR, STONE);
		assertTrue(reflectivity > 0.99);
	}

	@Test
	void commitPermeationTracksEnteredFromAndClearsOnReturnToAir() {
		Cast cast = new Cast(null, null, null);
		cast.impeded = AIR;
		cast.impededSet = true;
		cast.lastPermeationDistance = 1.0;

		cast.lastMaterial = STONE_MAT;
		cast.commitPermeation();

		assertEquals(STONE, cast.impeded);
		assertEquals(AIR, cast.enteredFrom);
		assertEquals(1.0, cast.solidTransitDistance, 1e-9);

		cast.lastPermeationDistance = 0.2;
		cast.lastMaterial = AIR_MAT;
		cast.commitPermeation();

		assertEquals(AIR, cast.impeded);
		assertNull(cast.enteredFrom);
		assertEquals(0.0, cast.solidTransitDistance, 1e-9);
	}
}
