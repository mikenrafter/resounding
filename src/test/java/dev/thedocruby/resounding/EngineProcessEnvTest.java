package dev.thedocruby.resounding;

import dev.thedocruby.resounding.config.PrecomputedConfig;
import dev.thedocruby.resounding.config.ResoundingConfig;
import net.fabricmc.api.EnvType;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineProcessEnvTest {

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.createGameVersion();
		Bootstrap.initialize();
		Engine.envType = EnvType.CLIENT;
	}

	@BeforeEach
	void activateConfig() throws CloneNotSupportedException {
		if (PrecomputedConfig.pConfig != null) {
			PrecomputedConfig.pConfig.deactivate();
		}
		ResoundingConfig config = new ResoundingConfig();
		config.effects.airAbsorption = 0.2;
		PrecomputedConfig.pConfig = new PrecomputedConfig(config);
	}

	@AfterEach
	void deactivateConfig() {
		if (PrecomputedConfig.pConfig != null) {
			PrecomputedConfig.pConfig.deactivate();
			PrecomputedConfig.pConfig = null;
		}
	}

	@Test
	void airAbsorptionFromConfigIsNotUnity() {
		assertNotEquals(1.0, PrecomputedConfig.pConfig.airAbsorptionHF, 1e-9,
				"config airAbsorption must map to a per-meter HF factor below 1.0");
		assertTrue(PrecomputedConfig.pConfig.airAbsorptionHF > 0.9,
				"air absorption HF should stay in a sensible open-air range");
	}

	@Test
	void distanceAttenuationUsesInverseSquare() {
		assertEquals(2.0, Engine.distanceAttenuationExponent(), 1e-9);
	}
}
