package dev.thedocruby.resounding;

import dev.thedocruby.resounding.raycast.Branch;
import dev.thedocruby.resounding.toolbox.ChunkChain;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class OctreePlantSchedulerTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private ChunkPos player = new ChunkPos(0, 0);
    private int radius = 10;
    private final List<OctreePlantScheduler.PlantJob> planted = new ArrayList<>();
    private OctreePlantScheduler scheduler;

    @BeforeEach
    void setUp() {
        planted.clear();
        player = new ChunkPos(0, 0);
        radius = 10;
        now.set(1_000_000L);
        scheduler = new OctreePlantScheduler(
                now::get,
                () -> player,
                () -> radius,
                Runnable::run,
                planted::add
        );
    }

    private OctreePlantScheduler.PlantJob job(int cx, int cz, int section) {
        ChunkChain chunk = new StubChunk();
        Branch root = new Branch(new BlockPos(cx << 4, 0, cz << 4), 16);
        return new OctreePlantScheduler.PlantJob(new ChunkPos(cx, cz), section, 0, chunk, root);
    }

    @Test
    void inRing_usesChebyshevDistance() {
        assertTrue(OctreePlantScheduler.inRing(new ChunkPos(0, 0), new ChunkPos(10, 0), 10));
        assertFalse(OctreePlantScheduler.inRing(new ChunkPos(0, 0), new ChunkPos(11, 0), 10));
        assertTrue(OctreePlantScheduler.inRing(new ChunkPos(0, 0), new ChunkPos(7, 7), 10));
        assertFalse(OctreePlantScheduler.inRing(new ChunkPos(0, 0), new ChunkPos(8, 8), 7));
        assertFalse(OctreePlantScheduler.inRing(null, new ChunkPos(0, 0), 10));
    }

    @Test
    void inRingLoad_goesToPriorityAndDrainsWithoutQuiet() {
        scheduler.noteChunkLoad();
        scheduler.schedule(job(0, 0, 1));
        assertEquals(1, scheduler.prioritySize());
        assertEquals(0, scheduler.deferredSize());
        assertFalse(scheduler.isQuiet());

        scheduler.onClientTick();
        assertEquals(1, planted.size());
        assertEquals(0, scheduler.prioritySize());
    }

    @Test
    void inRingLoad_jumpsToFrontOfPriority() {
        scheduler.schedule(job(1, 0, 0));
        scheduler.schedule(job(0, 0, 0));
        scheduler.onClientTick();
        assertEquals(2, planted.size());
        // last scheduled in-ring was addFirst, so (0,0) drains before (1,0)
        assertEquals(new ChunkPos(0, 0), planted.get(0).pos());
        assertEquals(new ChunkPos(1, 0), planted.get(1).pos());
    }

    @Test
    void outOfRing_waitsForQuiet() {
        scheduler.noteChunkLoad();
        scheduler.schedule(job(20, 0, 2));
        assertEquals(0, scheduler.prioritySize());
        assertEquals(1, scheduler.deferredSize());

        scheduler.onClientTick();
        assertTrue(planted.isEmpty());

        now.addAndGet(OctreePlantScheduler.QUIET_MS);
        scheduler.onClientTick();
        assertEquals(1, planted.size());
        assertEquals(0, scheduler.deferredSize());
    }

    @Test
    void newLoad_resetsQuietClock() {
        scheduler.noteChunkLoad();
        scheduler.schedule(job(20, 0, 0));
        now.addAndGet(OctreePlantScheduler.QUIET_MS - 1);
        scheduler.noteChunkLoad();
        now.addAndGet(OctreePlantScheduler.QUIET_MS - 1);
        scheduler.onClientTick();
        assertTrue(planted.isEmpty());

        now.addAndGet(2);
        scheduler.onClientTick();
        assertEquals(1, planted.size());
    }

    @Test
    void deferredPromotesWhenPlayerMovesNear() {
        scheduler.noteChunkLoad();
        scheduler.schedule(job(20, 0, 0));
        assertEquals(1, scheduler.deferredSize());

        player = new ChunkPos(20, 0);
        scheduler.onClientTick();
        assertEquals(1, planted.size());
        assertEquals(0, scheduler.deferredSize());
    }

    @Test
    void rescheduleSameSection_replacesQueuedEntry() {
        scheduler.schedule(job(20, 0, 3));
        Branch newer = new Branch(new BlockPos(20 << 4, 64, 0), 16);
        scheduler.schedule(new OctreePlantScheduler.PlantJob(
                new ChunkPos(20, 0), 3, 0, new StubChunk(), newer
        ));
        assertEquals(1, scheduler.deferredSize());
        // No noteChunkLoad yet → quiet vacuously; drain deferred.
        scheduler.onClientTick();
        assertEquals(1, planted.size());
        assertSame(newer, planted.get(0).root());
    }

    /** Minimal stand-in; plant consumer never calls through to the real mixin. */
    private static final class StubChunk implements ChunkChain {
        @Override public Branch getBranch(int y) { return null; }
        @Override public java.util.Map<Long, net.minecraft.util.shape.VoxelShape> getShapes() {
            return java.util.Map.of();
        }
        @Override public void set(int index, Branch branch) {}
        @Override public ChunkChain set(int plane, ChunkChain negative, ChunkChain positive) { return this; }
        @Override public ChunkChain set(int plane, int index, ChunkChain link) { return this; }
        @Override public ChunkChain get(int plane, int index) { return null; }
        @Override public ChunkChain traverse(int d, int plane) { return this; }
        @Override public ChunkChain access_(int tx, int tz) { return this; }
        @Override public ChunkChain access(int x, int z) { return this; }
        @Override public void initStorage() {}
        @Override public void replantOctrees() {}
        @Override public Branch layer(Branch root) { return root; }
    }
}
