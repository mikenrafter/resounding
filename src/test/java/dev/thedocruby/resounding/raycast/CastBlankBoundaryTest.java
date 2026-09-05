package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.config.PrecomputedConfig;
import dev.thedocruby.resounding.config.ResoundingConfig;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CastBlankBoundaryTest {

	@BeforeEach
	void activateConfig() throws CloneNotSupportedException {
		if (PrecomputedConfig.pConfig != null) {
			PrecomputedConfig.pConfig.deactivate();
		}
		PrecomputedConfig.pConfig = new PrecomputedConfig(new ResoundingConfig());
	}

	@AfterEach
	void deactivateConfig() {
		if (PrecomputedConfig.pConfig != null) {
			PrecomputedConfig.pConfig.deactivate();
			PrecomputedConfig.pConfig = null;
		}
	}

	@Test
	void blankClearsStaleReflectivitySoEngineCannotFollowDegenerateReflect() {
		Cast cast = new Cast(null, null, null, null);
		cast.lastBoundaryResolved = true;
		cast.lastReflectivity = 1.0;
		cast.lastTransmission = 0.0;

		cast.blank(new Vec3d(1, 2, 3), Cast.BlankReason.VACUUM, -16000.0);

		assertFalse(cast.lastBoundaryResolved);
		assertEquals(0.0, cast.lastReflectivity);
		assertEquals(0.0, cast.lastTransmission);
		assertEquals(Cast.BlankReason.VACUUM, cast.lastBlankReason);
		assertEquals(-16000.0, cast.lastBlankImpedance, 0.0);
		assertEquals(-16000.0, cast.lastResolvedImpedance, 0.0, "pre-clear Z kept for dLog");
		assertNotNull(cast.transmitted);
		assertNull(cast.transmitted.vector());
		assertNotNull(cast.reflected);
		assertNull(cast.reflected.vector());
	}

	@Test
	void vacuumImpedanceDetectsNonPositiveAndNonFinite() {
		assertTrue(Cast.isVacuumImpedance(0.0));
		assertTrue(Cast.isVacuumImpedance(-1.0));
		assertTrue(Cast.isVacuumImpedance(Double.NaN));
		assertFalse(Cast.isVacuumImpedance(426.9));
	}
}
