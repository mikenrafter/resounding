package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.config.PrecomputedConfig;
import dev.thedocruby.resounding.config.ResoundingConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CastImpededTest {

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
	void commitPermeationSetsImpededFromLastMaterial() {
		Cast cast = new Cast(null, null, null);
		cast.lastMaterial = new dev.thedocruby.resounding.material.Material(41471.0, 0.5, 1.0);
		assertFalse(cast.impededSet);

		cast.commitPermeation();

		assertTrue(cast.impededSet);
		assertEquals(41471.0, cast.impeded);
	}

	@Test
	void commitPermeationNoOpWhenLastMaterialMissing() {
		Cast cast = new Cast(null, null, null);
		cast.impeded = 426.9;
		cast.impededSet = true;

		cast.commitPermeation();

		assertEquals(426.9, cast.impeded);
	}
}
