package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.toolbox.SlotProfile;
import dev.thedocruby.resounding.toolbox.SoundProfile;
import net.fabricmc.api.EnvType;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.sound.SoundCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundEffectReadoutTest {

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.createGameVersion();
		Bootstrap.initialize();
	}

	@AfterEach
	void reset() {
		SoundEffectReadout.clear();
	}

	@Test
	void formatIncludesDirectAndReverbValues() {
		SoundProfile profile = new SoundProfile(
				42,
				0.75,
				0.5,
				new double[]{0.1, 0.2},
				new double[]{0.3, 0.4}
		);
		SoundEffectReadout.publish(
				"stone.break",
				SoundCategory.BLOCKS,
				profile,
				new SlotProfile(1, 0.2, 0.6),
				0.8,
				true,
				true
		);

		String formatted = SoundEffectReadout.format(SoundEffectReadout.latest());
		assertTrue(formatted.contains("stone.break"));
		assertTrue(formatted.contains("id=42"));
		assertTrue(formatted.contains("occ=80%"));
		assertTrue(formatted.contains("direct=0.75/0.50"));
		assertTrue(formatted.contains("rvb s1=0.20/0.60"));
	}
}
