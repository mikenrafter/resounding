package dev.thedocruby.resounding;

import dev.thedocruby.resounding.config.PrecomputedConfig;
import dev.thedocruby.resounding.config.ResoundingConfig;
import dev.thedocruby.resounding.toolbox.SlotProfile;
import net.fabricmc.api.EnvType;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Contract: {@link Engine#selectSlot} must pick the bin with the highest send gain,
 * including bin 1 when it wins over bin 0.
 */
class EngineSelectSlotTest {

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
		config.quality.reverbResolution = 4;
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
	void fallsBackToBinZeroWhenOnlyDryBinHasEnergy() {
		int resolution = PrecomputedConfig.pConfig.resolution;
		double[] sendGain = new double[resolution + 1];
		double[] sendCutoff = new double[resolution + 1];

		sendGain[0] = 0.8;
		for (int i = 1; i <= resolution; i++) {
			sendGain[i] = 0.0;
		}

		SlotProfile profile = Engine.selectSlot(sendGain, sendCutoff);

		assertEquals(0, profile.slot());
		assertEquals(0.8, profile.gain(), 1e-9);
	}

	@Test
	void weightedAverageSelectsHigherSlotWhenEnergySpreads() {
		int resolution = PrecomputedConfig.pConfig.resolution;
		double[] sendGain = new double[resolution + 1];
		double[] sendCutoff = new double[resolution + 1];

		sendGain[0] = 0.5;
		sendGain[resolution] = 0.5;
		for (int i = 1; i < resolution; i++) {
			sendGain[i] = 0.0;
		}

		SlotProfile profile = Engine.selectSlot(sendGain, sendCutoff);

		assertEquals(resolution / 2, profile.slot());
	}

	@Test
	void highestGainInBin1SelectsSlotNearWeightedCenter() {
		int resolution = PrecomputedConfig.pConfig.resolution;
		double[] sendGain = new double[resolution + 1];
		double[] sendCutoff = new double[resolution + 1];

		sendGain[0] = 0.1;
		sendGain[1] = 0.9;
		sendGain[2] = 0.2;
		for (int i = 3; i <= resolution; i++) {
			sendGain[i] = 0.05;
		}

		SlotProfile profile = Engine.selectSlot(sendGain, sendCutoff);

		assertEquals(1, profile.slot(), "weighted center favors bin 1 when it dominates");
		assertEquals(0.9, profile.gain(), 1e-9);
	}
}
