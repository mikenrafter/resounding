package dev.thedocruby.resounding.raycast;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CastReflectionPowerTest {

	// reflectivity is already the fraction of incident power reflected at the impedance
	// boundary (see Acoustics.reflection); reflectedPower must not attenuate beyond that,
	// or reflection-side energy stops summing to incident power alongside the transmitted side.
	@Test
	void reflectedPowerEqualsReflectivityTimesIncidentPower() {
		double reflectivity = 0.64;
		double incident = 128.0;
		double reflected = Cast.reflectedPower(reflectivity, incident);
		assertEquals(reflectivity * incident, reflected, 1e-9);
	}

	@Test
	void nearTotalReflectorsRetainMostEnergy() {
		double reflectivity = 0.99;
		double incident = 128.0;
		double reflected = Cast.reflectedPower(reflectivity, incident);
		assertTrue(reflected > 0.95 * reflectivity * incident);
	}

	// Real air impedance varies a bit depending on how it was baked (e.g. the MaterialRegistry.DEFAULT
	// fallback of 412.0 vs a fully-resolved air definition around 427); exiting a thin partition back
	// into "air-ish" impedance must still be recognized as the same medium.
	@Test
	void withinRelativeToleranceAcceptsAirLikeImpedanceDrift() {
		assertTrue(Cast.withinRelativeTolerance(412.0, 426.9));
	}

	@Test
	void withinRelativeToleranceRejectsARealMaterialMismatch() {
		assertFalse(Cast.withinRelativeTolerance(426.9, 32_676_640.0));
	}

	// A 1-block glass pane: entered from air, thin, exits back to air-like impedance -> pass-through.
	@Test
	void thinMembraneExitDetectedForAShortTraversalBackToTheOriginalMedium() {
		assertTrue(Cast.isThinMembraneExit(412.0, 1.0, 426.9));
	}

	// Traveling deep through a thick cavern of similarly-impedanced stone and hitting another stone
	// surface far later must NOT be treated as "the same thin partition" — that's a real second wall.
	@Test
	void thickTraversalIsNotTreatedAsAThinMembraneEvenWithMatchingImpedance() {
		assertFalse(Cast.isThinMembraneExit(8_000_000.0, 40.0, 8_000_000.0));
	}

	@Test
	void thinMembraneExitRequiresMatchingImpedance() {
		assertFalse(Cast.isThinMembraneExit(412.0, 1.0, 8_000_000.0));
	}

	@Test
	void thinMembraneExitRequiresAPreviousEntry() {
		assertFalse(Cast.isThinMembraneExit(null, 1.0, 426.9));
	}
}
