package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.Engine;
import dev.thedocruby.resounding.config.PrecomputedConfig;
import dev.thedocruby.resounding.config.ResoundingConfig;
import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.toolbox.ChunkChain;
import net.fabricmc.api.EnvType;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * RED (bug leak #2): {@link Cast#getBlock} has an early return —
 * {@code if (branch.size > 1) return branch;} — that hands back the raw coarse (possibly
 * polarized) branch directly, bypassing the live-leaf single-material resolution
 * ({@code liveLeaf(...)}) the rest of the method already computes unconditionally. Since
 * {@code getBlock} is the dominant path {@code Cast.raycast} uses to resolve any {@code frustumSize
 * < 2} beam (see {@code Cast.raycast}'s {@code lodBranch.size == 1} override), a genuinely
 * fine-resolution query must never come back as a coarse, polarized {@link Branch}.
 */
class CastGetBlockCoarseBranchPurityTest {

    private static final Material AIR = new Material(426.9, 1.0, 0.5);

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        Engine.envType = EnvType.CLIENT;
    }

    // Cast's constructor reads PrecomputedConfig.pConfig.frustumGrowthPerBlock unconditionally, so
    // any test that builds a live Cast needs an active pConfig — same fixture idiom as
    // PrecomputedConfigRayBudgetTest.
    @BeforeEach
    void activateConfig() throws CloneNotSupportedException {
        if (PrecomputedConfig.pConfig != null) {
            PrecomputedConfig.pConfig.deactivate();
        }
        PrecomputedConfig.pConfig = new PrecomputedConfig(new ResoundingConfig());
    }

    @AfterEach
    void deactivateConfig() {
        if (PrecomputedConfig.pConfig != null) {
            PrecomputedConfig.pConfig.deactivate();
            PrecomputedConfig.pConfig = null;
        }
    }

    @Test
    void getBlockNeverReturnsACoarsePrunedPolarizedBranch() {
        // Pruned (children == null) size-4 node the real lookup path (tree.get) lands on for any
        // position inside it, still carrying a polarized aggregate descriptor from before
        // OctreeManager.sameAcousticCell's same-Block shortcut collapsed it (known follow-up issue,
        // not under test here).
        Branch coarseRoot = new Branch(new BlockPos(0, 0, 0), 4, AIR);
        coarseRoot.bake(new Branch.NodeDescriptor(2_700_000.0, 200.0, 1_350_100.0, 0.75, new Vec3d(2, 0, 0)));

        WorldChunk chunkMock = Mockito.mock(WorldChunk.class, Mockito.withSettings().extraInterfaces(ChunkChain.class));
        Mockito.when(chunkMock.getBlockState(Mockito.any())).thenReturn(Blocks.AIR.getDefaultState());

        Cast cast = new Cast(null, coarseRoot, (ChunkChain) chunkMock);

        Branch result = cast.getBlock(new Vec3d(0.5, 0.5, 0.5));

        assertEquals(1, result.size, "getBlock must resolve to the genuine 1x1x1 leaf, not the coarse pruned node");
        assertNull(result.descriptor.polar(),
                "a genuine 1x1x1 leaf resolved through getBlock must never carry a polarization descriptor");
    }
}
