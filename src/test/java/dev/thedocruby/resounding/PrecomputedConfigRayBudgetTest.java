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
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrecomputedConfigRayBudgetTest {

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
		config.quality.envEvalRayBounces = 6;
		config.quality.reverbResolution = 20;
		config.quality.rayLength = 4.0;
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
	void rayBouncesAreNotCoupledToReverbResolution() {
		assertEquals(6, PrecomputedConfig.pConfig.nRayBounces);
	}

	@Test
	void maxTraceDistUsesRayLengthNotBounceCount() {
		double expected = 4.0 * 16 * Math.sqrt(2);
		assertEquals(expected, PrecomputedConfig.pConfig.maxTraceDist, 1e-6);
		assertTrue(PrecomputedConfig.pConfig.maxTraceDist < 6 * 16 * Math.sqrt(2) * 20,
				"trace distance should not scale with reverb resolution");
	}
}
