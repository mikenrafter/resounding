package dev.thedocruby.resounding;

// imports {
// internal {

import dev.thedocruby.resounding.openal.Context;
import dev.thedocruby.resounding.debug.CaptureBuffer;
import dev.thedocruby.resounding.raycast.Cast;
import dev.thedocruby.resounding.raycast.Hit;
import dev.thedocruby.resounding.raycast.Ray;
import dev.thedocruby.resounding.raycast.Renderer;
import dev.thedocruby.resounding.toolbox.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundListener;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Pair;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedList;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static dev.thedocruby.resounding.SoundClassifier.adjustSource;
import static dev.thedocruby.resounding.config.PrecomputedConfig.*;
// }
// }

@SuppressWarnings({"CommentedOutCode"})
// TODO: do more Javadoc
public class Engine {
	// static definitions {
	private Engine() { }

	public static Context root;
	public static EnvType envType = null;
	public static MinecraftClient mc;
	public static boolean isActive = false;


	// Per-evaluation state — bundled into a record and passed through the pipeline
	// to avoid mutable static fields that could be overwritten between sounds.

	/** Captures per-sound evaluation state created in play() and threaded through the pipeline. */
	private record SoundEvalContext(
			Vec3d soundPos,
			Vec3d listenerPos,
			int sourceID,
			ChunkChain soundChunk,
			boolean auxOnly
	) {}

	// Mixin bridge: set by recordLastSound(), consumed by play().
	// These statics exist solely because the SoundSystem mixin and Source mixin
	// fire at separate injection points and cannot pass data directly.
	private static String tag;
	private static SoundCategory category;
	private static SoundListener lastSoundListener;

	private static Set<Pair<Vec3d,Integer>> rays;
	public static Vec3d playerPos;
	public static boolean hasLoaded = false;

	public static void setRoot(Context context) {root=context;}

	@Environment(EnvType.CLIENT)
	public static void updateRays() {
		final double rate = 2 * Math.PI / 1.618033988 /* phi */;
		final double epsilon =
			pConfig.nRays >= 600000 ? 214  :
			pConfig.nRays >= 400000 ? 75   :
			pConfig.nRays >= 11000  ? 27   :
			pConfig.nRays >= 890    ? 10   :
			pConfig.nRays >= 177    ? 3.33 :
			pConfig.nRays >= 24     ? 1.33 :
								 0.33 ;
		final double phiHelper = pConfig.nRays - 1 + 2*epsilon;

		// calculate starting vectors
		rays = IntStream.range(0, pConfig.nRays).parallel().unordered().mapToObj(i -> {
			// trig stuff
			final double theta = rate * i;
			final double phi = Math.acos(1 - 2*(i + epsilon) / phiHelper);
			final double sP = Math.sin(phi);

			return new Pair<>(new Vec3d(
					Math.cos(theta) * sP,
					Math.sin(theta) * sP,
					Math.cos(phi)
			), i);
		}).collect(Collectors.toSet());
	}

	@Environment(EnvType.CLIENT)
	public static void recordLastSound(@NotNull SoundInstance sound, SoundListener listener) {
		category = sound.getCategory();
		tag = sound.getId().getPath();
		lastSoundListener = listener;
	}

	@Contract("_, _, _, _ -> _")
	@Environment(EnvType.CLIENT) // wraps playSound() in order to process SVC audio chunks
	public static void play_svc(Context context, Vec3d soundPos, int sourceIDIn, boolean auxOnlyIn) {
		tag = "voice-chat";
		play(context, soundPos, sourceIDIn, auxOnlyIn);
	}

	@Environment(EnvType.CLIENT)
	public static void play(Context context, Vec3d pos, int sourceIDIn, boolean auxOnlyIn) {
		assert Engine.isActive;
		// Capture mixin-bridged metadata as locals immediately
		final String currentTag = tag;
		final SoundCategory currentCategory = category;
		final SoundListener currentListener = lastSoundListener;

		long startTime = 0;
		if (pConfig.pLog) startTime = System.nanoTime();

		// adjust sound position based on category/tag
		Vec3d soundPos = adjustSource(currentCategory, currentTag, pos);
		if (soundPos == null || mc.player == null || mc.world == null) {
			if (pConfig.dLog) Utils.LOGGER.info("skipped tracing sound \"{}\"", currentTag);
			return;
		}

		// get pose
		playerPos = mc.player.getPos().add(new Vec3d(0, mc.player.getEyeHeight(mc.player.getPose()), 0));
		Vec3d listenerPos = currentListener.getTransform().position();
		double maxDist = Math.min(
				Math.min(
						Math.min(
								mc.options.getSimulationDistance().getValue(),
								mc.options.getViewDistance().getValue()),
						pConfig.soundSimulationDistance
				) * 16, // chunk
				pConfig.maxTraceDist / 2); // diameter -> radius
		// too far/quiet
		if (Math.max(playerPos.distanceTo(soundPos), listenerPos.distanceTo(soundPos)) > maxDist) {
			if (pConfig.dLog) Utils.LOGGER.info("skipped tracing sound \"{}\"", currentTag);
			return;
		}

		// bundle per-sound state for the evaluation pipeline
		ChunkChain soundChunk = (ChunkChain) mc.world.getChunk((int) soundPos.x>>4, (int) soundPos.z>>4);
		SoundEvalContext evalCtx = new SoundEvalContext(soundPos, listenerPos, sourceIDIn, soundChunk, auxOnlyIn);
		boolean isGentle = SoundClassifier.gentlePattern.matcher(currentTag).matches();

		if (pConfig.dLog) Utils.LOGGER.info(
				"Sound {"
					+ "\n  Player:   " + playerPos
					+ "\n  Listener: " + listenerPos
					+ "\n  Source:   " + soundPos
					+ "\n  ID:       " + sourceIDIn
					+ "\n  Name:     " + currentCategory + "." + currentTag
					+ "\n  }"
		);

		final EnvData env = evalEnv(evalCtx);

		// CORE PIPELINE
		try {
			setEnv(context, processEnv(env, evalCtx), isGentle);
		} catch (Exception e) {
			Utils.LOGGER.error("Resounding: failed to apply sound profile", e);
		}

		if (pConfig.pLog) Utils.LOGGER.info("Total calculation time for sound {}: {} milliseconds",
				currentTag, (System.nanoTime() - startTime) / 10e5D);
	}

	@Environment(EnvType.CLIENT)
	private static @NotNull LinkedList<Hit> airspace(@NotNull Pair<Vec3d,Integer> input, double amplitude, Vec3d targetPosition, SoundEvalContext ctx) {
		return raycast(input, amplitude, input.getLeft().distanceTo(targetPosition), targetPosition,
				// always permeate!
				(Cast c, LinkedList<Hit> r) -> false,
				ctx
		);
	}

	@Environment(EnvType.CLIENT)
	private static @NotNull LinkedList<Hit> raycast(@NotNull Pair<Vec3d,Integer> input, double amplitude, SoundEvalContext ctx) {
		return raycast(input, amplitude,
				(Cast cast, LinkedList<Hit> results) -> cast.reflected.power() > cast.transmitted.power()
						// TODO use better method for permeation preference near start
						* (2 - (pConfig.nRayBounces - results.size()) / (double) pConfig.nRayBounces),
				ctx
		);
	}

	@Environment(EnvType.CLIENT)
	private static @NotNull LinkedList<Hit> raycast(@NotNull Pair<Vec3d,Integer> input, double amplitude, BiFunction<Cast, LinkedList<Hit>, Boolean> reflect, SoundEvalContext ctx) {
		return raycast(input, amplitude, Double.POSITIVE_INFINITY, null, reflect, ctx);
	}

	@Environment(EnvType.CLIENT)
	private static @NotNull LinkedList<Hit> raycast(@NotNull Pair<Vec3d,Integer> input, double amplitude, double maxLength, Vec3d targetPosition, BiFunction<Cast, LinkedList<Hit>, Boolean> reflect, SoundEvalContext ctx) {
		int id = input.getRight(); // for debug purposes
		Vec3d vector = input.getLeft();
		LinkedList<Hit> results = new LinkedList<>();
		Cast cast = new Cast(mc.world, null, ctx.soundChunk(), targetPosition);
		// launch initial ray & always permeate first
		cast.raycast(ctx.soundPos(), vector, amplitude);
		if (cast.transmitted == null || cast.transmitted.vector() == null) {
			return results;
		}
		Ray ray = new Ray(amplitude, cast.transmitted.position(), cast.transmitted.vector(), cast.transmitted.length());

		double length = cast.transmitted.length();
		Vec3d prior = ctx.soundPos(); // used solely for debugging
		byte reflected = 0; // used to stop rays that are trapped between two walls
		// while power, within max search range & iterate bounces
		while (ray.power() > 1 && maxLength > length && results.size() < pConfig.nRayBounces) {
			// debugging output
			if (pConfig.dRays) Renderer.addSoundBounceRay(
					prior, ray.position(),
					cast.lastOctantColor,
					results.size(),
					ctx.sourceID(),
					cast.lastMaterial,
					cast.lastReflectivity == null ? 0.0 : cast.lastReflectivity,
					cast.lastTransmission == null ? 0.0 : cast.lastTransmission,
					ray.power()
			);
			prior = ray.position();

			// cast ray
			cast.raycast(ray.position(), ray.vector(), ray.power());
			if (cast.transmitted == null) {
				break;
			}

			if (pConfig.dLog && cast.lastMaterial != null) {
				Utils.LOGGER.info(
						"Resounding: bounce #{} material Z={} R={} T={} power={}",
						results.size(),
						String.format("%.1f", cast.lastMaterial.impedance()),
						String.format("%.3f", cast.lastReflectivity == null ? 0.0 : cast.lastReflectivity),
						String.format("%.3f", cast.lastTransmission == null ? 0.0 : cast.lastTransmission),
						String.format("%.1f", ray.power())
				);
			}

			//* handle properties {
			// TODO handle splits & replace:
			//  reflect instead of permeate, when logical
			if (reflect.apply(cast, results)) {
				// stop rays stuck between two walls (not moving)
				// num, not bool -> (3D) edges & corners
				if (reflected++ > 2) break;
				// record bounce results
				results.add(new Hit
						/*end pos  */( ray.position()
						/*length   */, length
						/*shared   */, 0 // TODO figure out & populate
						/*distance */, cast.reflected.position().distanceTo(ctx.listenerPos())
						/*segment  */, length+cast.reflected.length()
						/*surface  */, cast.reflected.power()/ray.power()
						/*amplitude*/, cast.reflected.power()
						));

				ray = cast.reflected;
				if (ray == null || ray.vector() == null) {
					break;
				}
				continue;
			}
			if (cast.transmitted.vector() == null) {
				break;
			}
			double advance = cast.transmitted.length();
			if (advance < 1e-6) {
				break;
			}
			ray = cast.transmitted;
			length += advance;
			reflected = 0;
			// } */
		}
		return results;
	}

	@Environment(EnvType.CLIENT)
	private static @NotNull Set<OccludedRayData> throwOcclRay(
			@NotNull Vec3d sourcePos,
			@NotNull Vec3d sinkPos,
			ChunkChain chunk
	) {
		if (mc == null || mc.world == null || chunk == null) {
			return Collections.emptySet();
		}

		double totalDistance = sourcePos.distanceTo(sinkPos);
		if (totalDistance < 1e-4) {
			return Collections.emptySet();
		}

		Vec3d direction = sinkPos.subtract(sourcePos).multiply(1.0 / totalDistance);
		Cast cast = new Cast(mc.world, null, chunk, sinkPos);

		double power = 128.0;
		Vec3d position = sourcePos;
		double traveled = 0.0;
		double blocked = 0.0;
		int legs = 0;
		int guard = 512;

		while (power > 1.0 && traveled < totalDistance - 1e-4 && guard-- > 0) {
			cast.raycast(position, direction, power);
			if (cast.transmitted == null || cast.transmitted.vector() == null) {
				break;
			}

			double step = Math.min(cast.transmitted.length(), totalDistance - traveled);
			if (step < 1e-6) {
				break;
			}

			if (cast.lastReflectivity != null && cast.lastReflectivity > 0.001) {
				blocked += cast.lastReflectivity * (power / 128.0);
				legs++;
			}

			traveled += step;
			power = cast.transmitted.power();
			position = cast.transmitted.position();
		}

		double permeation = MathHelper.clamp(power / 128.0, 0.0, 1.0);
		double occlusion = 1.0 - permeation;

		if (pConfig.oLog) {
			Utils.LOGGER.info(
					"Resounding: direct occlusion legs={} dist={} permeation={} blocked={}",
					legs,
					String.format("%.1f", totalDistance),
					String.format("%.3f", permeation),
					String.format("%.3f", blocked)
			);
		}

		return Set.of(new OccludedRayData(
				legs,
				totalDistance,
				occlusion,
				new double[]{totalDistance},
				new double[]{totalDistance},
				new double[]{blocked},
				new double[]{occlusion}
		));
	}

	@Environment(EnvType.CLIENT)
	private static @NotNull EnvData evalEnv(SoundEvalContext ctx) {
		CaptureBuffer.INSTANCE.onSoundEvalStart();
		try {
		// Throw rays around
		// TODO implement tagging system here
		Consumer<String> logger = pConfig.log ? (pConfig.eLog ? Utils.LOGGER::info : Utils.LOGGER::debug) : x -> {};
		Set<LinkedList<Hit>> reflRays;
		logger.accept("Sampling environment with "+pConfig.nRays+" seed rays...");
		reflRays = rays.stream().parallel().unordered().map((ray) -> Engine.raycast(ray, 128, ctx)).collect(Collectors.toSet());
		if (pConfig.eLog) {
			int rayCount = 0;
			for (LinkedList<Hit> reflRay : reflRays) {
				rayCount += reflRay.size() * 2 + 1;
			}
			logger.accept("Total number of rays casted: "+rayCount);
		}

		// TODO: Occlusion. Also, add occlusion profiles.
		// Step rays from sound to listener
		Set<OccludedRayData> occlRays = throwOcclRay(ctx.soundPos(), ctx.listenerPos(), ctx.soundChunk());

		// Pass data to post
		EnvData data = new EnvData(reflRays, occlRays);
		logger.accept("Raw Environment data:\n"+data);
		return data;
		} finally {
			CaptureBuffer.INSTANCE.onSoundEvalEnd();
		}
	}

	@Contract("_, _ -> new")
	@Environment(EnvType.CLIENT)
	private static @NotNull SoundProfile processEnv(final EnvData data, SoundEvalContext ctx) {
		final double airAbsorptionHF = 1.0;
		double directGain = (ctx.auxOnly() ? 0 : 1) * Math.pow(airAbsorptionHF, ctx.listenerPos().distanceTo(ctx.soundPos()));

		double directPermeation = 1.0;
		for (OccludedRayData occl : data.occlRays()) {
			directPermeation *= (1.0 - MathHelper.clamp(occl.totalOcclusion(), 0.0, 1.0));
		}
		directGain *= directPermeation;
		double directCutoff = Math.pow(directPermeation, pConfig.globalAbsHFRcp);

		if (data.reflRays().isEmpty()) {
			return new SoundProfile(
					ctx.sourceID(),
					MathHelper.clamp(directGain, 0.0, 1.0),
					MathHelper.clamp(directCutoff, 0.0, 1.0),
					new double[pConfig.resolution + 1],
					new double[pConfig.resolution + 1]
			);
		}

		double bounces = 0.0D;
		double missed = 0.0D;
		for (LinkedList<Hit> ray : data.reflRays()) {
			bounces += ray.size();
			if (!ray.isEmpty() && ray.getLast().amplitude() < 1) {
				missed++;
			}
		}
		missed /= pConfig.nRays;
		if (bounces < 1e-6) {
			bounces = 1.0;
		}

		// TODO: Does this perform better in parallel? (test using Spark)
		double sharedSum = 0.0D;
		final double[] sendGain = new double[pConfig.resolution + 1];
		// NOTE temporary solution, will be removed during ray / redirection rework
		// TODO fix during ray / redirection rework
		double amplitude = 0.0D;
		// TODO explain
		for (LinkedList<Hit> ray : data.reflRays()) {

			final int size = ray.size();
			double smoothSharedEnergy = 0;
			double smoothSharedDistance = 0;
			int iterations = 0;
			for (Hit hit : ray) {
				if (!pConfig.fastShared) { // in-depth calculation
					if (hit.shared() == 1) {
						smoothSharedEnergy = 1;
						smoothSharedDistance = hit.distance();
						continue;
					}

					// Should be 0, old algorithm always returned 0 here. Will this behave better with proper value?
					smoothSharedEnergy = hit.reflect();
					smoothSharedDistance = hit.distance();
					// TODO use a better method to identify where occlusion / airspace rays should go
					// halfway through each ray, send occlusion ray
					if (++iterations == size / 2) {
						LinkedList<Hit> occlusion = airspace(new Pair<>(hit.position(), -1), hit.amplitude(), playerPos, ctx);
						double permeation = occlusion.isEmpty() ? hit.amplitude() : occlusion.getLast().amplitude();
						amplitude = Math.max(permeation, amplitude);
						// TODO determine accuracy of this method
						if (permeation > 1) sharedSum += size;
					}
				}

				final double playerEnergy =
						MathHelper.clamp(
						hit.amplitude() * (
								pConfig.fastShared
								? Math.pow(airAbsorptionHF, hit.length() + hit.distance())
								/ Math.pow(hit.length() + hit.distance(), 2.0D * missed)

								: smoothSharedEnergy
								* Math.pow(airAbsorptionHF, hit.length() + smoothSharedDistance)
								/ Math.pow(hit.length() + smoothSharedDistance, 2.0D * missed)
							),
						0, 1);

				final double bounceEnergy = MathHelper.clamp(
						hit.amplitude()
								* Math.pow(airAbsorptionHF, hit.length())
								/ Math.pow(hit.length(), 2.0D * missed),
						java.lang.Double.MIN_VALUE, 1);

				// TODO modify to use individual speed of sound in mediums
				final double bounceTime = hit.length() / speedOfSound;

				sendGain[
						bounceEnergyBin(bounceEnergy, bounceTime)
						] += playerEnergy;

			}
		}

		sharedSum /= bounces;
		final double[] sendCutoff = new double[pConfig.resolution+1];
		for (int i = 0; i <= pConfig.resolution; i++) {
			// NOTE, removed pConfig.waterFilt logic, as it's superseded by new occlusion method
			sendGain[i] = MathHelper.clamp(sendGain[i] * (pConfig.fastShared ? sharedSum : 1) * pConfig.resolution / bounces * pConfig.globalRvrbGain, 0, 1.0 - java.lang.Double.MIN_NORMAL);
			sendCutoff[i] = Math.pow(sendGain[i], pConfig.globalRvrbHFRcp); // TODO: make sure this actually works.
		}

		// inverse of occlusion
		double permeation = amplitude / 128;

		directGain *= Math.pow(airAbsorptionHF, ctx.listenerPos().distanceTo(ctx.soundPos()))
				/ Math.pow(ctx.listenerPos().distanceTo(ctx.soundPos()), 2 * missed)
				* MathHelper.lerp(permeation, 1, sharedSum);
		directCutoff = Math.pow(MathHelper.clamp(directGain, 1e-6, 1.0), pConfig.globalAbsHFRcp);

		SoundProfile profile = new SoundProfile(
				ctx.sourceID(),
				MathHelper.clamp(directGain, 0.0, 1.0),
				MathHelper.clamp(directCutoff, 0.0, 1.0),
				sendGain,
				sendCutoff
		);

		if (pConfig.log) Utils.LOGGER.info("Processed sound profile:\n{}", profile);

		return profile;
	}

	@Environment(EnvType.CLIENT)
	public static void setEnv(Context context, final @NotNull SoundProfile profile, boolean isGentle) {
		if (profile.sendGain().length != pConfig.resolution + 1 || profile.sendCutoff().length != pConfig.resolution + 1) {
			throw new IllegalArgumentException("Error: Reverb parameter count does not match reverb resolution!");
		}

		final SlotProfile finalSend = selectSlot(profile.sendGain(), profile.sendCutoff());

		if (pConfig.eLog || pConfig.dLog) {
			Utils.LOGGER.info("Final reverb settings:\n{}", finalSend);
		}

		context.update(finalSend, profile, isGentle);
	}


	@Contract("_, _ -> new")
	@Environment(EnvType.CLIENT)
	public static @NotNull SlotProfile selectSlot(double[] sendGain, double[] sendCutoff) {
		if (pConfig.fastPick) { // TODO: find cause of block.lava.ambient NaN
			int slot = 0;
			double max = sendGain[0];
			for (int i = 1; i <= pConfig.resolution; i++) if (sendGain[i] > max) {
				slot=i;
				max = sendGain[i];
			}

			final int iavg = slot;
			// Different fast selection method, can't decide which one is better.
			// TODO: Do something with this.
            /* if (false) {
				double sum = 0;
				double weightedSum = 0;
				for (int i = 1; i <= pConfig.resolution; i++) {
					sum += sendGain[i];
					weightedSum += i * sendGain[i];
				}
				iavg = (int) Math.round(MathHelper.clamp(weightedSum / sum, 0, pConfig.resolution));
			} */

			return iavg > 0
				? new SlotProfile(iavg, sendGain[iavg], sendCutoff[iavg])
				: new SlotProfile(0, sendGain[0], sendCutoff[0]);
		}
		// TODO: Slot selection logic will go here. See https://www.desmos.com/calculator/v5bt1gdgki
        /*
		final double mk = m-k;
		double selected = factorial(m)/(factorial(k)-factorial(mk))*Math.pow(1-x,mk)*Math.min(1,Math.max(0,Math.pow(x,k)));
		 */
		return new SlotProfile(0, 0, 0);
	}

	/** Maps bounce energy and path time to a reverb preset bin without log(1.0) singularities. */
	@Environment(EnvType.CLIENT)
	static int bounceEnergyBin(double bounceEnergy, double bounceTime) {
		if (bounceTime < 1e-9) {
			return 0;
		}
		double energy = MathHelper.clamp(bounceEnergy, 1e-12, 1.0 - 1e-12);
		double rt60 = energy >= 1.0 - 1e-12
				? pConfig.maxDecayTime
				: Math.min(pConfig.maxDecayTime, -bounceTime / Math.log(energy));
		double fraction = rt60 / pConfig.maxDecayTime;
		return MathHelper.clamp((int) Math.round(fraction * pConfig.resolution), 0, pConfig.resolution);
	}

}
