package dev.thedocruby.resounding;

import net.minecraft.util.math.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recent server-side chunk-generation activity used to keep client octree planting off the
 * generation frontier. Entries expire after {@link #ACTIVE_MS}.
 */
public final class ChunkGenerationActivity {
    private ChunkGenerationActivity() {}

    /** How long a generated chunk (and its Chebyshev-1 neighborhood) blocks nearby plants. */
    public static final long ACTIVE_MS = 2000L;
    /** Chebyshev radius around a generated chunk that is treated as "near generation". */
    public static final int NEIGHBOR_RADIUS = 1;

    private static final ConcurrentHashMap<Long, Long> generatedAtMs = new ConcurrentHashMap<>();

    public static void note(ChunkPos pos) {
        note(pos, System.currentTimeMillis());
    }

    public static void note(ChunkPos pos, long nowMs) {
        generatedAtMs.put(ChunkPos.toLong(pos.x, pos.z), nowMs);
    }

    public static void clear() {
        generatedAtMs.clear();
    }

    /** True if {@code pos} is within {@link #NEIGHBOR_RADIUS} of any still-active generation. */
    public static boolean nearGeneration(@Nullable ChunkPos pos) {
        return nearGeneration(pos, System.currentTimeMillis());
    }

    public static boolean nearGeneration(@Nullable ChunkPos pos, long nowMs) {
        if (pos == null) {
            return false;
        }
        prune(nowMs);
        for (Map.Entry<Long, Long> e : generatedAtMs.entrySet()) {
            if (nowMs - e.getValue() > ACTIVE_MS) {
                continue;
            }
            ChunkPos gen = new ChunkPos(e.getKey());
            int dx = Math.abs(pos.x - gen.x);
            int dz = Math.abs(pos.z - gen.z);
            // in the immediate 5 neighbors
            if (Math.max(dx, dz) <= NEIGHBOR_RADIUS * 5) {
                return true;
            }
        }
        return false;
    }

    public static boolean isBusy(long nowMs) {
        prune(nowMs);
        for (Long t : generatedAtMs.values()) {
            if (nowMs - t <= ACTIVE_MS) {
                return true;
            }
        }
        return false;
    }

    private static void prune(long nowMs) {
        Iterator<Map.Entry<Long, Long>> it = generatedAtMs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> e = it.next();
            if (nowMs - e.getValue() > ACTIVE_MS) {
                it.remove();
            }
        }
    }
}
