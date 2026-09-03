package dev.thedocruby.resounding;

import dev.thedocruby.resounding.raycast.Branch;
import dev.thedocruby.resounding.toolbox.ChunkChain;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Gates octree planting: in-ring sections plant ASAP (priority lane, ignores the quiet
 * timer) unless they sit near active chunk generation; out-of-ring sections wait in a
 * deferred queue until {@link #QUIET_MS} elapses with no chunk loads. See
 * {@code CHUNK_PLANT_DEBOUNCE.md}.
 */
@Environment(EnvType.CLIENT)
public final class OctreePlantScheduler {

    public static final long QUIET_MS = 2000L;
    /** Extra chunks beyond {@code soundSimulationDistance} that still plant immediately. */
    public static final int RING_BUFFER_CHUNKS = 2;
    static final int DRAIN_PER_TICK = 8;

    public record PlantJob(ChunkPos pos, int sectionIndex, int materialGeneration, ChunkChain chunk, Branch root) {
        long key() {
            return packKey(pos.x, pos.z, sectionIndex);
        }
    }

    private final ArrayDeque<PlantJob> priority = new ArrayDeque<>();
    private final ArrayDeque<PlantJob> deferred = new ArrayDeque<>();
    private final LongSupplier nowMs;
    private final Supplier<@Nullable ChunkPos> playerChunk;
    private final IntSupplier ringRadius;
    /** (chunkPos, nowMs) → true when planting should wait for generation to settle. */
    private final BiPredicate<ChunkPos, Long> nearGeneration;
    private final Executor plantExecutor;
    private final Consumer<PlantJob> jobConsumer;

    private long lastChunkLoadMs;
    private boolean lastChunkLoadSet;

    public OctreePlantScheduler(
            LongSupplier nowMs,
            Supplier<@Nullable ChunkPos> playerChunk,
            IntSupplier ringRadius,
            BiPredicate<ChunkPos, Long> nearGeneration,
            Executor plantExecutor,
            Consumer<PlantJob> jobConsumer
    ) {
        this.nowMs = Objects.requireNonNull(nowMs);
        this.playerChunk = Objects.requireNonNull(playerChunk);
        this.ringRadius = Objects.requireNonNull(ringRadius);
        this.nearGeneration = Objects.requireNonNull(nearGeneration);
        this.plantExecutor = Objects.requireNonNull(plantExecutor);
        this.jobConsumer = Objects.requireNonNull(jobConsumer);
        this.lastChunkLoadMs = nowMs.getAsLong();
        this.lastChunkLoadSet = false;
    }

    public static long packKey(int chunkX, int chunkZ, int sectionIndex) {
        return (((long) chunkX) << 42) ^ (((long) chunkZ) << 16) ^ (sectionIndex & 0xffffL);
    }

    public static boolean inRing(@Nullable ChunkPos player, ChunkPos chunk, int radius) {
        if (player == null || radius < 0) {
            return false;
        }
        int dx = Math.abs(chunk.x - player.x);
        int dz = Math.abs(chunk.z - player.z);
        return Math.max(dx, dz) <= radius;
    }

    public synchronized void noteChunkLoad() {
        lastChunkLoadMs = nowMs.getAsLong();
        lastChunkLoadSet = true;
    }

    /** Classifies {@code job} with the shared ring metric; in-ring goes to the priority front. */
    public synchronized void schedule(PlantJob job) {
        removeKey(job.key());
        long now = nowMs.getAsLong();
        boolean ring = inRing(playerChunk.get(), job.pos(), ringRadius.getAsInt());
        if (ring && !nearGeneration.test(job.pos(), now)) {
            priority.addFirst(job);
        } else {
            deferred.addLast(job);
        }
    }

    public synchronized void onClientTick() {
        long now = nowMs.getAsLong();
        promoteInRing(now);
        drainPriority(now, DRAIN_PER_TICK);
        if (isQuiet()) {
            drainDeferred(now, DRAIN_PER_TICK);
        }
    }

    public synchronized int prioritySize() {
        return priority.size();
    }

    public synchronized int deferredSize() {
        return deferred.size();
    }

    public synchronized boolean isQuiet() {
        if (!lastChunkLoadSet) {
            return true;
        }
        return nowMs.getAsLong() - lastChunkLoadMs >= QUIET_MS;
    }

    private void promoteInRing(long now) {
        ChunkPos player = playerChunk.get();
        int radius = ringRadius.getAsInt();
        Iterator<PlantJob> it = deferred.iterator();
        while (it.hasNext()) {
            PlantJob job = it.next();
            if (inRing(player, job.pos(), radius) && !nearGeneration.test(job.pos(), now)) {
                it.remove();
                priority.addFirst(job);
            }
        }
    }

    private void drainPriority(long now, int limit) {
        for (int i = 0; i < limit; i++) {
            PlantJob job = priority.pollFirst();
            if (job == null) {
                return;
            }
            if (nearGeneration.test(job.pos(), now)) {
                deferred.addLast(job);
                continue;
            }
            plantExecutor.execute(() -> jobConsumer.accept(job));
        }
    }

    private void drainDeferred(long now, int limit) {
        int planted = 0;
        int inspected = 0;
        int maxInspect = deferred.size();
        while (planted < limit && inspected < maxInspect) {
            PlantJob job = deferred.pollFirst();
            if (job == null) {
                return;
            }
            inspected++;
            if (nearGeneration.test(job.pos(), now)) {
                deferred.addLast(job);
                continue;
            }
            plantExecutor.execute(() -> jobConsumer.accept(job));
            planted++;
        }
    }

    private void removeKey(long key) {
        priority.removeIf(j -> j.key() == key);
        deferred.removeIf(j -> j.key() == key);
    }
}
