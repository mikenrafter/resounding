package dev.thedocruby.resounding.raycast;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CastReflectionPowerTest {

	@Test
	void reflectedPowerIsBelowIncidentReflectivityForPartialReflectors() {
		double reflectivity = 0.64;
		double incident = 128.0;
		double reflected = Cast.reflectedPower(reflectivity, incident);
		assertTrue(reflected < reflectivity * incident,
				"reflected path must lose energy to surface absorption");
		assertTrue(reflected > 0.0);
	}

	@Test
	void nearTotalReflectorsRetainMostEnergy() {
		double reflectivity = 0.99;
		double incident = 128.0;
		double reflected = Cast.reflectedPower(reflectivity, incident);
		assertTrue(reflected > 0.95 * reflectivity * incident);
	}
}
