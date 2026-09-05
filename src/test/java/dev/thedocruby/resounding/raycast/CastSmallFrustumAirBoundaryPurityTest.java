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

/**
 * RED end-to-end regression: with {@code frustumSize < 2} ({@code FrustumLod.stepForSize} ->
 * requestedLod 1) and a beam whose split budget is exhausted, {@code Cast.raycast} must resolve a
 * genuine air:air boundary with zero reflectivity. It currently doesn't, because both leaks
 * documented on {@link Branch#virtualLodCell} and {@link Cast#getBlock} let a coarse, pruned,
 * polarized size-4 node survive all the way to the boundary-physics section of
 * {@link Cast#raycast} even though the beam only ever asked for a size-1 (lod 1) cell. The notable
 * -interaction gate (frustums-plan.md) then commits to a full reflect off what should have been
 * plain, unpolarized air, because {@code commitReflect(w)} sees a fully-aligned polarization vector
 * that a genuine leaf should never have carried in the first place.
 */
class CastSmallFrustumAirBoundaryPurityTest {

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
    void smallFrustumAirToAirBoundaryMustNotSpuriouslyReflect() {
        // Size-4 pruned/homogeneous-by-shortcut node labeled air, but still carrying a polarized
        // aggregate descriptor from before OctreeManager.sameAcousticCell's same-Block shortcut
        // collapsed it (known follow-up issue, not under test here). blendCoefficient=0.75 and a
        // polar vector fully aligned with the ray both push the notable-interaction gate's blend
        // weight to 1.0 (fully committed to the "most common" endpoint) once splits are exhausted.
        Branch root = new Branch(new BlockPos(0, 0, 0), 4, AIR);
        root.bake(new Branch.NodeDescriptor(2_700_000.0, AIR.impedance(), 1_350_213.45, 0.75, new Vec3d(1, 0, 0)));

        WorldChunk chunkMock = Mockito.mock(WorldChunk.class, Mockito.withSettings().extraInterfaces(ChunkChain.class));
        Mockito.when(chunkMock.getBlockState(Mockito.any())).thenReturn(Blocks.AIR.getDefaultState());
        ChunkChain chunkChain = (ChunkChain) chunkMock;
        Mockito.when(chunkChain.access(Mockito.anyInt(), Mockito.anyInt())).thenReturn(chunkChain);
        Mockito.when(chunkChain.getBranch(Mockito.anyInt())).thenReturn(root);

        Cast cast = new Cast(null, root, chunkChain);
        cast.impededSet = true;
        cast.impeded = AIR.impedance();
        cast.frustumSize = 1.5; // < 2 -> FrustumLod.stepForSize gives requestedLod == 1
        // Exhaust every split tier so the notable-interaction gate falls to the flat commit cutoff
        // (Physics.commitReflect) instead of permeating past a "notable" polarized boundary.
        cast.beamBudget = BeamBudget.full().consumeAt(1.0).consumeAt(2.0).consumeAt(4.0).consumeAt(8.0);

        cast.raycast(new Vec3d(0.5, 0.5, 0.5), new Vec3d(1, 0, 0), 1.0);

        assertEquals(1, cast.lastBranchSize, "a frustumSize < 2 beam must resolve at a size-1 cell");
        assertEquals(0.0, cast.lastReflectivity, 1e-9,
                "a genuine air:air boundary must not reflect just because the coarse ancestor it was "
                        + "carved from still carries a polarization descriptor");
    }
}
