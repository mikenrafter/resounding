package dev.thedocruby.resounding.effects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReverbPresetsTest {

	@Test
	void presetDelaysSpanAudibleRoomSizeRange() {
		double tSmall = Reverb.presetT(1, 16);
		double tLarge = Reverb.presetT(16, 16);

		float reflectionsSmall = Reverb.presetReflectionsDelay(tSmall);
		float reflectionsLarge = Reverb.presetReflectionsDelay(tLarge);
		float lateSmall = Reverb.presetLateReverbDelay(tSmall);
		float lateLarge = Reverb.presetLateReverbDelay(tLarge);

		assertTrue(reflectionsSmall >= 0.005f && reflectionsLarge <= 0.080f,
				"reflections delay must span ~5–80 ms, got " + reflectionsSmall + " .. " + reflectionsLarge);
		assertTrue(lateSmall >= 0.008f && lateLarge <= 0.053f,
				"late reverb delay must span ~8–53 ms, got " + lateSmall + " .. " + lateLarge);
		assertTrue(reflectionsLarge > reflectionsSmall);
		assertTrue(lateLarge > lateSmall);
	}

	@Test
	void presetGainIncreasesWithRoomSize() {
		float gainSmall = Reverb.presetMasterGain(Reverb.presetT(2, 16));
		float gainLarge = Reverb.presetMasterGain(Reverb.presetT(16, 16));
		assertTrue(gainLarge > gainSmall);
		assertTrue(gainSmall >= 0.25f && gainLarge <= 0.9f);
	}
}
