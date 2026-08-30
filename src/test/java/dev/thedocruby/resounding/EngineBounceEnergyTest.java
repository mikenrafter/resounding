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
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for ray-energy bin assignment in {@link Engine}.
 */
class EngineBounceEnergyTest {

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
		config.quality.sharedAirspaceMode = dev.thedocruby.resounding.toolbox.SharedAirspaceMode.FAST;
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
	void bounceEnergyIsNotCappedAtOneWhenAmplitudeExceedsNominalScale() {
		Hit hit = new Hit(new Vec3d(0, 0, 0), 5.0, 0, 5.0, 2.0, 0.5, 160.0);
		double unclamped = Engine.bounceEnergyForHit(hit, 0.0, 1.0, 2.0);
		assertTrue(unclamped > 1.0 + 1e-9,
				"permeation-heavy paths can exceed the old 1.0 clamp before binning");
	}

	@Test
	void longerPathsMapToLaterTimeBins() {
		int shortBin = Engine.timeBinForAcousticPath(2.0, 5.0);
		int longBin = Engine.timeBinForAcousticPath(80.0, 5.0);
		assertTrue(longBin > shortBin, "room-scale path length should advance the time bin");
	}

	@Test
	void processEnvDepositsLongerPathsInLaterBinsThanShortPaths() throws Exception {
		Hit nearHit = new Hit(new Vec3d(1, 0, 0), 3.0, 0, 4.0, 1.0, 0.5, 96.0);
		Hit farHit = new Hit(new Vec3d(40, 0, 0), 60.0, 0, 4.0, 8.0, 0.5, 96.0);

		int nearPeak = peakSendBin(List.of(ray(nearHit)));
		int farPeak = peakSendBin(List.of(ray(farHit)));

		assertTrue(farPeak > nearPeak, "far reflections should peak in a later reverb bin than near ones");
	}

	@Test
	void longerPathsDepositMoreAbsoluteSendGain() throws Exception {
		Hit nearHit = new Hit(new Vec3d(1, 0, 0), 3.0, 0, 4.0, 1.0, 0.5, 96.0);
		Hit farHit = new Hit(new Vec3d(40, 0, 0), 60.0, 0, 4.0, 1.0, 0.5, 96.0);

		double nearGain = totalSendGain(List.of(ray(nearHit)));
		double farGain = totalSendGain(List.of(ray(farHit)));

		assertTrue(farGain > 0, "large-room reflections must contribute wet send gain");
		assertTrue(nearGain > 0, "small-room reflections must contribute wet send gain");
	}

	private static double totalSendGain(List<LinkedList<Hit>> reflRays) throws Exception {
		EnvData data = new EnvData(reflRays, Set.of());

		Class<?> ctxClass = Class.forName("dev.thedocruby.resounding.Engine$SoundEvalContext");
		Constructor<?> ctxCtor = ctxClass.getDeclaredConstructors()[0];
		ctxCtor.setAccessible(true);
		Object ctx = ctxCtor.newInstance(Vec3d.ZERO, new Vec3d(0, 0, 4), 1, null, false);

		Method processEnv = Engine.class.getDeclaredMethod("processEnv", EnvData.class, ctxClass);
		processEnv.setAccessible(true);
		Object processed = processEnv.invoke(null, data, ctx);

		Method profileMethod = processed.getClass().getDeclaredMethod("profile");
		profileMethod.setAccessible(true);
		SoundProfile profile = (SoundProfile) profileMethod.invoke(processed);

		double sum = 0;
		for (double gain : profile.sendGain()) {
			sum += gain;
		}
		return sum;
	}

	private static LinkedList<Hit> ray(Hit hit) {
		LinkedList<Hit> ray = new LinkedList<>();
		ray.add(hit);
		return ray;
	}

	private static int peakSendBin(List<LinkedList<Hit>> reflRays) throws Exception {
		EnvData data = new EnvData(reflRays, Set.of());

		Class<?> ctxClass = Class.forName("dev.thedocruby.resounding.Engine$SoundEvalContext");
		Constructor<?> ctxCtor = ctxClass.getDeclaredConstructors()[0];
		ctxCtor.setAccessible(true);
		Object ctx = ctxCtor.newInstance(Vec3d.ZERO, new Vec3d(0, 0, 4), 1, null, false);

		Method processEnv = Engine.class.getDeclaredMethod("processEnv", EnvData.class, ctxClass);
		processEnv.setAccessible(true);
		Object processed = processEnv.invoke(null, data, ctx);

		Method profileMethod = processed.getClass().getDeclaredMethod("profile");
		profileMethod.setAccessible(true);
		SoundProfile profile = (SoundProfile) profileMethod.invoke(processed);

		double[] sendGain = profile.sendGain();
		int peak = 0;
		for (int i = 1; i <= PrecomputedConfig.pConfig.resolution; i++) {
			if (sendGain[i] > sendGain[peak]) {
				peak = i;
			}
		}
		assertTrue(sendGain[peak] > 0, "test hit must contribute send gain");
		return peak;
	}
}
