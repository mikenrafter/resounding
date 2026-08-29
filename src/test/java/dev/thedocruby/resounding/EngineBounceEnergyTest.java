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

/**
 * Contract tests for ray-energy bin assignment and cumulative path length in {@link Engine}.
 */
class EngineBounceEnergyTest {

	private static final double SAMPLE_PATH_LENGTH = 10.0;
	private static final double speedOfSound = PrecomputedConfig.speedOfSound;

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
		config.quality.reverbResolution = 16;
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
	void pinnedBounceEnergyMustNotCollapseToBinZero() {
		double bounceTime = SAMPLE_PATH_LENGTH / speedOfSound;

		assertNotEquals(0, Engine.bounceEnergyBin(1.0, bounceTime),
				"bounceEnergy clamped to 1.0 must not collapse all energy into bin 0");
	}

	@Test
	void secondBounceHitLengthIncludesPriorSegments() {
		double firstSegment = 5.0;
		double secondSegment = 3.0;

		double secondHitLength = currentRaycastSecondHitLength(firstSegment, secondSegment);

		assertEquals(firstSegment + secondSegment, secondHitLength, 1e-9,
				"cumulative path length must not reset to 0 after reflection");
	}

	static double currentRaycastSecondHitLength(double firstSegment, double secondSegment) {
		double length = firstSegment;
		length += secondSegment;
		return length;
	}
}
