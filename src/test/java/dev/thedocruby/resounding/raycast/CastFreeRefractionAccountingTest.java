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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task E accounting: a free graze-refraction transmit step must not invent reflect power / bounce
 * accounting, must keep ordinary permeation loss, and must tag {@link Cast#lastFreeRefraction}
 * while resetting {@link Cast#lastGrowthDeferred} on the next cast.
 */
class CastFreeRefractionAccountingTest {

    private static final Material AIR = new Material(426.9, 1.0, 0.5);
    private static final double DELTA = 1e-9;

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
    void freeRefractionTransmitKeepsPermeationCostAndTagsFlags() {
        // Size-2 polarized air cell: same most/least impedance so R stays air:air (=0), but polar
        // (0,+1) is present so wouldDoubleCover can fire on growth. frustumSize 3 → lod step 2.
        Branch root = new Branch(new BlockPos(0, 0, 0), 2, AIR);
        root.bake(new Branch.NodeDescriptor(
                AIR.impedance(), AIR.impedance(), AIR.impedance(), Double.NaN, new Vec3d(0, 1, 0)));

        WorldChunk chunkMock = Mockito.mock(WorldChunk.class, Mockito.withSettings().extraInterfaces(ChunkChain.class));
        Mockito.when(chunkMock.getBlockState(Mockito.any())).thenReturn(Blocks.AIR.getDefaultState());
        ChunkChain chunkChain = (ChunkChain) chunkMock;
        Mockito.when(chunkChain.access(Mockito.anyInt(), Mockito.anyInt())).thenReturn(chunkChain);
        Mockito.when(chunkChain.getBranch(Mockito.anyInt())).thenReturn(root);

        Cast cast = new Cast(null, root, chunkChain);
        cast.impededSet = true;
        cast.impeded = AIR.impedance();
        cast.frustumSize = 3.0;

        cast.raycast(new Vec3d(0.5, 0.5, 0.5), new Vec3d(1, 0, 0), 1.0);

        assertTrue(cast.lastFreeRefraction, "double-cover growth attempt must tag free refraction");
        assertTrue(cast.lastGrowthDeferred, "growth must be deferred for applyFrustumStep");
        assertEquals(0.0, cast.reflected.power(), DELTA,
                "graze-refraction lives on the transmit leg — no reflect power / bounce");
        assertEquals(0.0, cast.lastReflectivity, DELTA);

        // Ordinary permeation still applies (no extra loss, but not a free ride on transmission).
        double expectedT = Cast.transmissionForBoundary(0.0, AIR.permeation(), cast.transmitted.length());
        assertEquals(expectedT, cast.lastTransmission, DELTA);

        double sizeBefore = cast.frustumSize;
        cast.applyFrustumStep(cast.transmitted.length(), PrecomputedConfig.pConfig.frustumGrowthPerBlock,
                cast.lastTransmission, true);
        assertEquals(sizeBefore, cast.frustumSize, DELTA, "deferred step must not grow or shrink");

        // Next ordinary cast clears the deferral flag at entry.
        cast.raycast(new Vec3d(2.5, 0.5, 0.5), new Vec3d(1, 0, 0), 1.0);
        assertFalse(cast.lastGrowthDeferred, "next raycast must reset lastGrowthDeferred");
    }
}
