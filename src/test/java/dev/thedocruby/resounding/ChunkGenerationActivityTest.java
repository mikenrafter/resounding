package dev.thedocruby.resounding;

import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkGenerationActivityTest {

    @BeforeEach
    void clear() {
        ChunkGenerationActivity.clear();
    }

    @Test
    void notesBlockSelfAndChebyshevNeighbors() {
        long t = 1_000_000L;
        ChunkGenerationActivity.note(new ChunkPos(10, 20), t);
        assertTrue(ChunkGenerationActivity.nearGeneration(new ChunkPos(10, 20), t));
        assertTrue(ChunkGenerationActivity.nearGeneration(new ChunkPos(11, 20), t));
        assertTrue(ChunkGenerationActivity.nearGeneration(new ChunkPos(10, 21), t));
        assertTrue(ChunkGenerationActivity.nearGeneration(new ChunkPos(11, 21), t));
        assertFalse(ChunkGenerationActivity.nearGeneration(new ChunkPos(12, 20), t));
    }

    @Test
    void expiresAfterActiveWindow() {
        long t = 5_000L;
        ChunkGenerationActivity.note(new ChunkPos(0, 0), t);
        assertTrue(ChunkGenerationActivity.nearGeneration(new ChunkPos(0, 0), t + ChunkGenerationActivity.ACTIVE_MS));
        assertFalse(ChunkGenerationActivity.nearGeneration(new ChunkPos(0, 0), t + ChunkGenerationActivity.ACTIVE_MS + 1));
    }
}
