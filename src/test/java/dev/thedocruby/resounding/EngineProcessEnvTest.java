package dev.thedocruby.resounding;

import dev.thedocruby.resounding.config.PrecomputedConfig;
import dev.thedocruby.resounding.config.ResoundingConfig;
import dev.thedocruby.resounding.raycast.Hit;
import dev.thedocruby.resounding.toolbox.EnvData;
import dev.thedocruby.resounding.toolbox.SoundProfile;
import net.fabricmc.api.EnvType;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

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
	void fastSharedAirspaceModeStillProducesNonZeroSendGain() throws Exception {
		PrecomputedConfig.pConfig.deactivate();
		ResoundingConfig config = new ResoundingConfig();
		config.quality.sharedAirspaceMode = dev.thedocruby.resounding.toolbox.SharedAirspaceMode.FAST;
		PrecomputedConfig.pConfig = new PrecomputedConfig(config);

		Hit hit = new Hit(new Vec3d(1, 0, 0), 5.0, 0, 5.0, 5.0, 0.5, 64.0);
		LinkedList<Hit> ray = new LinkedList<>();
		ray.add(hit);
		List<LinkedList<Hit>> reflRays = List.of(ray);
		EnvData data = new EnvData(reflRays, Set.of());

		Class<?> ctxClass = Class.forName("dev.thedocruby.resounding.Engine$SoundEvalContext");
		Constructor<?> ctxCtor = ctxClass.getDeclaredConstructors()[0];
		ctxCtor.setAccessible(true);
		Object ctx = ctxCtor.newInstance(Vec3d.ZERO, new Vec3d(0, 0, 5), 1, null, false);

		Method processEnv = Engine.class.getDeclaredMethod("processEnv", EnvData.class, ctxClass);
		processEnv.setAccessible(true);
		Object processed = processEnv.invoke(null, data, ctx);

		Method profileMethod = processed.getClass().getDeclaredMethod("profile");
		profileMethod.setAccessible(true);
		SoundProfile profile = (SoundProfile) profileMethod.invoke(processed);

		assertTrue(Arrays.stream(profile.sendGain()).anyMatch(gain -> gain > 0),
				"FAST shared-airspace mode must still route hit energy into sendGain");
	}
}
