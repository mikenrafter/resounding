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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
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

	/** Result of {@link #processEnv} including direct-path permeation for debug readout. */
	private record ProcessedSound(SoundProfile profile, double directPermeation) {}

	// Mixin bridge: set by recordLastSound(), consumed by play().
	// These statics exist solely because the SoundSystem mixin and Source mixin
	// fire at separate injection points and cannot pass data directly.
	private static String tag;
	private static SoundCategory category;
	private static SoundListener lastSoundListener;

	private static Set<Pair<Vec3d,Integer>> rays;
	public static Vec3d playerPos;
	public static boolean hasLoaded = false;

	/** Cap debug line segments per sound so a burst of effects cannot flood the live buffer. */
	private static final int MAX_DEBUG_TRACE_RAYS = 128;

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
		play(context, pos, sourceIDIn, auxOnlyIn, tag, category, lastSoundListener);
	}

	@Environment(EnvType.CLIENT)
	private static void play(
			Context context,
			Vec3d pos,
			int sourceIDIn,
			boolean auxOnlyIn,
			String currentTag,
			SoundCategory currentCategory,
			SoundListener currentListener
	) {
		assert Engine.isActive;
		if (!pConfig.reverbEnabled && !pConfig.occlusionEnabled) {
			return;
		}

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
		ChunkChain soundChunk = (ChunkChain) mc.world.getChunk(MathHelper.floor(soundPos.x) >> 4, MathHelper.floor(soundPos.z) >> 4);
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
			ProcessedSound processed = processEnv(env, evalCtx);
			setEnv(context, processed, isGentle, currentTag, currentCategory);
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
		return raycast(input, amplitude, pConfig.maxTraceDist, null,
				(Cast cast, LinkedList<Hit> results) -> {
					if (cast.lastReflectivity != null && cast.lastReflectivity <= 0.0) {
						return false;
					}
					return cast.reflected.power() > cast.transmitted.power()
							* (2 - (pConfig.nRayBounces - results.size()) / (double) pConfig.nRayBounces);
				},
				ctx
		);
	}

	@Environment(EnvType.CLIENT)
	private static @NotNull LinkedList<Hit> raycast(@NotNull Pair<Vec3d,Integer> input, double amplitude, BiFunction<Cast, LinkedList<Hit>, Boolean> reflect, SoundEvalContext ctx) {
		return raycast(input, amplitude, Double.POSITIVE_INFINITY, null, reflect, ctx);
	}

	@Environment(EnvType.CLIENT)
	private static @NotNull LinkedList<Hit> raycast(@NotNull Pair<Vec3d,Integer> input, double amplitude, double maxLength, Vec3d targetPosition, BiFunction<Cast, LinkedList<Hit>, Boolean> reflect, SoundEvalContext ctx) {
		int id = input.getRight();
		Vec3d vector = input.getLeft();
		LinkedList<Hit> results = new LinkedList<>();
		Cast cast = new Cast(mc.world, null, ctx.soundChunk(), targetPosition);
		cast.originBlock = BlockPos.ofFloored(ctx.soundPos());
		String terminationReason = "ok";
		// Only kept when dLog is on, so the consecutive-reflect guard can show whether a ray made
		// real geometric progress between bounces or was stuck re-resolving the same spot.
		java.util.ArrayList<Vec3d> trail = pConfig.dLog ? new java.util.ArrayList<>() : null;
		if (trail != null) trail.add(ctx.soundPos());

		cast.raycast(ctx.soundPos(), vector, amplitude);
		if (cast.transmitted == null || cast.transmitted.vector() == null) {
			logRayTermination(id, "initial cast left the known world", results, ctx.soundPos());
			return results;
		}
		if (!cast.commitEmissionExit(cast.transmitted.position(), cast.transmitted.vector())) {
			logRayTermination(id, "emission exited into vacuum", results, cast.transmitted.position());
			return results;
		}
		Ray ray = new Ray(amplitude, cast.transmitted.position(), cast.transmitted.vector(), cast.transmitted.length());

		double pathLength = cast.transmitted.length();
		double segmentLength = pathLength;
		Vec3d prior = ctx.soundPos();
		byte reflected = 0;
		RayDebugTail debugTail = new RayDebugTail();
		debugTail.emit(ctx, cast, id, prior, cast.transmitted.position(), amplitude, results.size(),
				!(ray.power() > 1 && maxLength > pathLength && results.size() < pConfig.nRayBounces));
		prior = cast.transmitted.position();
		while (true) {
			if (!(ray.power() > 1 && maxLength > pathLength && results.size() < pConfig.nRayBounces)) {
				terminationReason = "budget exhausted";
				break;
			}
			if (trail != null) trail.add(ray.position());
			cast.raycast(ray.position(), ray.vector(), ray.power());
			if (cast.transmitted == null) {
				debugTail.emit(ctx, cast, id, prior, ray.position(), ray.power(), results.size(), true);
				terminationReason = "left the known world";
				break;
			}

			// Always log during a reflect run (reflected > 0), regardless of id, so a stuck run's
			// material/impedance is visible for every ray, not just the id<4 sample.
			if (pConfig.dLog && (id < 4 || reflected > 0)) {
				Utils.LOGGER.info(
						"Resounding: ray #{} bounce #{} node={}³ mode={} pos={} material={} Zprev={} Z={} R={} T={} power={}",
						id,
						results.size(),
						cast.lastBranchSize,
						cast.lastShapeMode ? "SHAPE" : "VOXEL",
						formatPos(ray.position()),
						cast.lastMaterialLabel == null ? "?" : cast.lastMaterialLabel,
						String.format("%.1f", cast.lastPriorImpedance),
						cast.lastMaterial == null ? "-" : String.format("%.1f", cast.lastMaterial.impedance()),
						String.format("%.3f", cast.lastReflectivity == null ? 0.0 : cast.lastReflectivity),
						String.format("%.3f", cast.lastTransmission == null ? 0.0 : cast.lastTransmission),
						String.format("%.1f", ray.power())
				);
			}

			recordReflectHitIfAny(cast, results, ray, pathLength, segmentLength, ctx.listenerPos());

			if (reflect.apply(cast, results)) {
				if (reflected++ > 2) {
					debugTail.emit(ctx, cast, id, prior, ray.position(), ray.power(), results.size(), true);
					terminationReason = trail != null
							? "3 consecutive reflects (" + (cast.lastShapeMode ? "SHAPE" : "VOXEL")
									+ ") trail=" + formatTrail(trail)
							: "3 consecutive reflects";
					break;
				}
				pathLength += cast.reflected.length();
				segmentLength = 0;
				ray = cast.reflected;
				if (ray == null || ray.vector() == null) {
					debugTail.emit(ctx, cast, id, prior, prior, ray == null ? 0 : ray.power(), results.size(), true);
					terminationReason = "reflected ray had no direction";
					break;
				}
				boolean continues = ray.power() > 1
						&& maxLength > pathLength
						&& results.size() < pConfig.nRayBounces;
				debugTail.emit(ctx, cast, id, prior, ray.position(), ray.power(), results.size(), !continues);
				prior = ray.position();
				if (!continues) {
					terminationReason = "budget exhausted after reflect";
					break;
				}
				continue;
			}
			if (cast.transmitted.vector() == null) {
				debugTail.emit(ctx, cast, id, prior, ray.position(), ray.power(), results.size(), true);
				terminationReason = "transmitted ray had no direction";
				break;
			}
			double advance = cast.transmitted.length();
			pathLength += advance;
			segmentLength += advance;
			ray = cast.transmitted;
			cast.commitPermeation();
			boolean continues = ray.power() > 1
					&& maxLength > pathLength
					&& results.size() < pConfig.nRayBounces
					&& cast.transmitted.vector() != null;
			debugTail.emit(ctx, cast, id, prior, ray.position(), ray.power(), results.size(), !continues);
			prior = ray.position();
			reflected = 0;
			if (!continues) {
				terminationReason = "budget exhausted after transmit";
				break;
			}
		}
		debugTail.overlayTerminator(ctx, cast, id);
		logRayTermination(id, terminationReason, results, prior);
		return results;
	}

	/**
	 * Records reflected energy for reverb binning whenever this cast produced a reflective boundary,
	 * regardless of whether propagation follows the reflected or transmitted branch.
	 */
	@Environment(EnvType.CLIENT)
	private static void recordReflectHitIfAny(
			Cast cast,
			LinkedList<Hit> results,
			Ray ray,
			double pathLength,
			double segmentLength,
			Vec3d listenerPos
	) {
		if (cast.lastReflectivity == null || cast.lastReflectivity <= 0.0
				|| cast.reflected == null || cast.reflected.power() <= 0.0) {
			return;
		}
		results.add(new Hit(
				ray.position(),
				pathLength,
				0,
				cast.reflected.position().distanceTo(listenerPos),
				segmentLength,
				cast.lastReflectivity,
				cast.reflected.power()
		));
	}

	/** Per-ray lifetime summary — logged for every ray (not just the id&lt;4 sample) so a ray that
	 *  dies unexpectedly (e.g. the 3-consecutive-reflect guard) can be attributed to a position and
	 *  mode without reconstructing it from the id-gated per-bounce lines. */
	@Environment(EnvType.CLIENT)
	private static void logRayTermination(int id, String reason, LinkedList<Hit> results, Vec3d lastPosition) {
		if (!pConfig.dLog) {
			return;
		}
		Utils.LOGGER.info(
				"Resounding: ray #{} terminated ({}) bounces={} pos={}",
				id,
				reason,
				results.size(),
				formatPos(lastPosition)
		);
	}

	private static String formatPos(Vec3d pos) {
		return String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f", pos.x, pos.y, pos.z);
	}

	/** Last few breadcrumb positions, oldest first, so a stuck ray shows as repeated/near-identical
	 *  entries and a genuinely-progressing one shows as distinct positions. */
	private static String formatTrail(java.util.List<Vec3d> trail) {
		int start = Math.max(0, trail.size() - 6);
		StringBuilder sb = new StringBuilder();
		for (int i = start; i < trail.size(); i++) {
			if (i > start) sb.append(" -> ");
			sb.append(formatPos(trail.get(i)));
		}
		return sb.toString();
	}

	/** Tracks the last debug segment per ray so a white overlay can mark the true path end. */
	private static final class RayDebugTail {
		private Vec3d start;
		private Vec3d end;
		private double power;
		private int bounce;
		private boolean terminated;

		void emit(
				SoundEvalContext ctx,
				Cast cast,
				int rayId,
				Vec3d segmentStart,
				Vec3d segmentEnd,
				double segmentPower,
				int bounceIndex,
				boolean segmentTerminated
		) {
			if (!pConfig.dRays || rayId >= MAX_DEBUG_TRACE_RAYS) {
				return;
			}
			emitDebugSegment(ctx, cast, segmentStart, segmentEnd, segmentPower, bounceIndex, segmentTerminated);
			this.start = segmentStart;
			this.end = segmentEnd;
			this.power = segmentPower;
			this.bounce = bounceIndex;
			this.terminated = segmentTerminated;
		}

		void overlayTerminator(SoundEvalContext ctx, Cast cast, int rayId) {
			if (!pConfig.dRays || rayId >= MAX_DEBUG_TRACE_RAYS || end == null || terminated) {
				return;
			}
			Renderer.addTerminatorCross(end);
		}
	}

	@Environment(EnvType.CLIENT)
	private static void emitDebugSegment(
			SoundEvalContext ctx,
			Cast cast,
			Vec3d start,
			Vec3d end,
			double power,
			int bounceIndex,
			boolean terminated
	) {
		Renderer.addSoundBounceRay(
				start,
				end,
				cast.lastOctantColor,
				bounceIndex,
				ctx.sourceID(),
				cast.lastMaterial,
				cast.lastReflectivity == null ? 0.0 : cast.lastReflectivity,
				cast.lastTransmission == null ? 0.0 : cast.lastTransmission,
				power,
				cast.lastPriorImpedance,
				cast.lastBranchSize,
				cast.lastMaterialLabel,
				terminated
		);
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
			cast.commitPermeation();
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
		Consumer<String> logger = pConfig.log ? (pConfig.eLog ? Utils.LOGGER::info : Utils.LOGGER::debug) : x -> {};
		List<LinkedList<Hit>> reflRays = List.of();
		if (pConfig.reverbEnabled) {
			logger.accept("Sampling environment with "+pConfig.nRays+" seed rays...");
			reflRays = rays.stream().parallel().unordered().map((ray) -> Engine.raycast(ray, 128, ctx)).toList();
			if (pConfig.eLog) {
				int rayCount = 0;
				for (LinkedList<Hit> reflRay : reflRays) {
					rayCount += reflRay.size() * 2 + 1;
				}
				logger.accept("Total number of rays casted: "+rayCount);
			}
		}

		Set<OccludedRayData> occlRays = pConfig.occlusionEnabled
				? throwOcclRay(ctx.soundPos(), ctx.listenerPos(), ctx.soundChunk())
				: Collections.emptySet();

		// Pass data to post
		EnvData data = new EnvData(reflRays, occlRays);
		if (pConfig.log) {
			logger.accept("Raw Environment data:\n" + data);
		}
		return data;
		} finally {
			CaptureBuffer.INSTANCE.onSoundEvalEnd();
		}
	}

	@Contract("_, _ -> new")
	@Environment(EnvType.CLIENT)
	private static @NotNull ProcessedSound processEnv(final EnvData data, SoundEvalContext ctx) {
		final double airAbsorptionHF = pConfig.airAbsorptionHF;
		double directGain = (ctx.auxOnly() ? 0 : 1) * Math.pow(airAbsorptionHF, ctx.listenerPos().distanceTo(ctx.soundPos()));

		double directPermeation = 1.0;
		if (pConfig.occlusionEnabled) {
			for (OccludedRayData occl : data.occlRays()) {
				directPermeation *= (1.0 - MathHelper.clamp(occl.totalOcclusion(), 0.0, 1.0));
			}
		}
		directGain *= directPermeation;
		double directCutoff = Math.pow(directPermeation, pConfig.globalAbsHFRcp);

		if (!pConfig.reverbEnabled || data.reflRays().isEmpty()) {
			return new ProcessedSound(
					new SoundProfile(
							ctx.sourceID(),
							MathHelper.clamp(directGain, 0.0, 1.0),
							MathHelper.clamp(directCutoff, 0.0, 1.0),
							new double[pConfig.resolution + 1],
							new double[pConfig.resolution + 1]
					),
					directPermeation
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
		missed /= data.reflRays().size();
		if (bounces < 1e-6) {
			bounces = 1.0;
		}

		// TODO: Does this perform better in parallel? (test using Spark)
		double sharedSum = 0.0D;
		final double[] sendGain = new double[pConfig.resolution + 1];
		final double[] sendCutoff = new double[pConfig.resolution + 1];
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
				if (!pConfig.fastShared && pConfig.occlusionEnabled) { // in-depth calculation
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

				final double legLength = Math.max(hit.segment(), 1e-6);
				final double pathLength = Math.max(hit.length(), 1e-6);

				final double playerEnergy =
						MathHelper.clamp(
						hit.amplitude() * (
								pConfig.fastShared
								? Math.pow(airAbsorptionHF, pathLength + hit.distance())
								/ Math.pow(pathLength + hit.distance(), 2.0D * missed)

								: smoothSharedEnergy
								* Math.pow(airAbsorptionHF, pathLength + smoothSharedDistance)
								/ Math.pow(pathLength + smoothSharedDistance, 2.0D * missed)
							),
						0, 1);

				final int timeBin = timeBinForAcousticPath(pathLength, hit.distance());

				sendGain[timeBin] += playerEnergy;

			}
		}

		sharedSum /= bounces;
		for (int i = 0; i <= pConfig.resolution; i++) {
			// NOTE, removed pConfig.waterFilt logic, as it's superseded by new occlusion method
			// sharedSum is only ever accumulated in the !fastShared branch above; fastShared mode
			// already folds the equivalent per-hit falloff into playerEnergy directly, so it must
			// skip this multiplier (1) rather than apply the always-zero sharedSum computed by the
			// path it never takes — the inverted ternary here zeroed every sendGain bin in the
			// (default) FAST shared-airspace mode.
			sendGain[i] = MathHelper.clamp(sendGain[i] * (pConfig.fastShared ? 1 : sharedSum) * pConfig.rcpNRays * pConfig.globalRvrbGain, 0, 1.0 - java.lang.Double.MIN_NORMAL);
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

		return new ProcessedSound(profile, directPermeation);
	}

	static double bounceEnergyForHit(Hit hit, double missed, double airAbsorptionHF, double legLength) {
		return Math.max(
				(hit.amplitude() / 128.0)
						* Math.pow(airAbsorptionHF, legLength)
						/ Math.pow(Math.max(legLength, 1.0), 2.0D * missed),
				java.lang.Double.MIN_VALUE);
	}

	static double energyWeightForHit(double bounceEnergy, double pathLength) {
		double bounceTime = pathLength / speedOfSound;
		double energyForBin = Math.pow(
				Math.max(bounceEnergy, java.lang.Double.MIN_VALUE),
				pConfig.maxDecayTime / Math.max(bounceTime, 1e-4) * pConfig.energyFix
		);
		if (energyForBin >= 1.0) {
			return 1.0;
		}
		return MathHelper.clamp(
				1.0 / Utils.logBase(Math.max(energyForBin, minEnergy), minEnergy) / pConfig.resolution,
				0, 1);
	}

	static int timeBinForAcousticPath(double pathLength, double listenerDistance) {
		double acousticPath = pathLength + listenerDistance;
		double logPath = Math.log1p(acousticPath);
		double logMaxPath = Math.log1p(pConfig.maxTraceDist);
		return MathHelper.clamp(
				(int) (logPath / logMaxPath * pConfig.resolution),
				0, pConfig.resolution);
	}

	@Environment(EnvType.CLIENT)
	public static void setEnv(
			Context context,
			final @NotNull ProcessedSound processed,
			boolean isGentle,
			String soundTag,
			SoundCategory soundCategory
	) {
		final SoundProfile profile = processed.profile();
		if (profile.sendGain().length != pConfig.resolution + 1 || profile.sendCutoff().length != pConfig.resolution + 1) {
			throw new IllegalArgumentException("Error: Reverb parameter count does not match reverb resolution!");
		}

		final SlotProfile finalSend = pConfig.reverbEnabled
				? selectSlot(profile.sendGain(), profile.sendCutoff())
				: new SlotProfile(0, 0, 1.0);

		if (pConfig.eLog || pConfig.dLog) {
			Utils.LOGGER.info("Final reverb settings:\n{}", finalSend);
			if (pConfig.reverbEnabled) {
				int peakBin = 0;
				double peakGain = profile.sendGain()[0];
				for (int i = 1; i <= pConfig.resolution; i++) {
					if (profile.sendGain()[i] > peakGain) {
						peakGain = profile.sendGain()[i];
						peakBin = i;
					}
				}
				Utils.LOGGER.info(
						"Resounding: reverb peak bin={} gain={} cutoff={} direct={}/{}",
						peakBin,
						String.format("%.4f", peakGain),
						String.format("%.4f", profile.sendCutoff()[peakBin]),
						String.format("%.4f", profile.directGain()),
						String.format("%.4f", profile.directCutoff())
				);
			}
		}

		context.update(finalSend, profile, isGentle);

		if (pConfig.dRays || pConfig.eLog) {
			dev.thedocruby.resounding.debug.SoundEffectReadout.publish(
					soundTag,
					soundCategory,
					profile,
					finalSend,
					processed.directPermeation(),
					pConfig.reverbEnabled,
					pConfig.occlusionEnabled
			);
		}
	}


	@Contract("_, _ -> new")
	@Environment(EnvType.CLIENT)
	public static @NotNull SlotProfile selectSlot(double[] sendGain, double[] sendCutoff) {
		if (pConfig.fastPick) { // TODO: find cause of block.lava.ambient NaN
			double sum = 0.0;
			double weightedSum = 0.0;
			for (int i = 0; i <= pConfig.resolution; i++) {
				sum += sendGain[i];
				weightedSum += i * sendGain[i];
			}
			if (sum <= 0.0) {
				return new SlotProfile(0, sendGain[0], sendCutoff[0]);
			}
			int slot = (int) Math.round(MathHelper.clamp(weightedSum / sum, 0, pConfig.resolution));
			return new SlotProfile(slot, sendGain[slot], sendCutoff[slot]);
		}
		// TODO: Slot selection logic will go here. See https://www.desmos.com/calculator/v5bt1gdgki
        /*
		final double mk = m-k;
		double selected = factorial(m)/(factorial(k)-factorial(mk))*Math.pow(1-x,mk)*Math.min(1,Math.max(0,Math.pow(x,k)));
		 */
		return new SlotProfile(0, 0, 0);
	}

}
