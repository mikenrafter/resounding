package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.MaterialRegistry;
import dev.thedocruby.resounding.fixture.SectionChunks;
import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.tag.Ident;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.chunk.WorldChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 RED tests for {@link PatchAggregator}'s exposed-face patch list (frustums-plan.md
 * "Acoustic patch aggregation pass"): per exposed block face, tag normal, centroid, area, material
 * — independent of {@code OctreeManager.growOctree}. Follows the same {@code SectionChunks}/
 * {@code MaterialRegistry.publish} fixture precedent as {@code OctreeGrowPolarBakeTest} (mirrors
 * {@code growOctree(WorldChunk, ...)}'s own signature shape).
 */
class PatchAggregatorTest {

    private static final Material STONE = new Material(2700.0, 0.5, 1.0);
    private static final Material AIR = new Material(1.2, 1.0, 0.5);
    /** Non-opaque block that is still acoustically solid (state=1). */
    private static final Material GLASS = new Material(2500.0, 0.95, 1.0);
    /** Gas-state material must never contribute patches. */
    private static final Material GAS = new Material(1.2, 1.0, 0.5);

    private static BlockState stoneState;
    private static BlockState airState;
    private static BlockState glassState;

    private MaterialRegistry.Snapshot priorMaterials;

    private static final Vec3i[] AXES = {
            new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0),
            new Vec3i(0, 1, 0), new Vec3i(0, -1, 0),
            new Vec3i(0, 0, 1), new Vec3i(0, 0, -1),
    };

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        stoneState = Blocks.STONE.getDefaultState();
        airState = Blocks.AIR.getDefaultState();
        glassState = Blocks.GLASS.getDefaultState();
    }

    @BeforeEach
    void publishMaterials() {
        priorMaterials = MaterialRegistry.snapshot();
        MaterialRegistry.publish(Map.ofEntries(
                Map.entry(Ident.parse("minecraft:stone"), STONE),
                Map.entry(Ident.parse("minecraft:air"), AIR),
                Map.entry(Ident.parse("minecraft:glass"), GLASS)
        ));
    }

    @AfterEach
    void restoreMaterials() {
        MaterialRegistry.restore(priorMaterials);
    }

    private static void fillUniform(SectionChunks.SectionFixture fixture, BlockState state) {
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    fixture.setLocal(x, y, z, state);
                }
            }
        }
    }

    private static Vec3d faceCentroid(BlockPos block, Vec3i normal) {
        double cx = block.getX() + 0.5 + normal.getX() * 0.5;
        double cy = block.getY() + 0.5 + normal.getY() * 0.5;
        double cz = block.getZ() + 0.5 + normal.getZ() * 0.5;
        return new Vec3d(cx, cy, cz);
    }

    @Test
    void singleIsolatedBlock_producesExactlySixExposedFaces_oneAxisEach() {
        BlockPos sectionOrigin = new BlockPos(0, 0, 0);
        BlockPos block = sectionOrigin.add(5, 5, 5);
        SectionChunks.SectionFixture fixture = SectionChunks.fixture(sectionOrigin);
        fillUniform(fixture, airState);
        fixture.setLocal(5, 5, 5, stoneState);
        WorldChunk chunk = fixture.chunk();

        List<Patch> patches = PatchAggregator.buildPatches(chunk, sectionOrigin);

        assertEquals(6, patches.size(), "an isolated block exposes all 6 faces");
        for (Vec3i axis : AXES) {
            Patch expected = new Patch(faceCentroid(block, axis), axis, 1.0, STONE);
            assertTrue(patches.contains(expected), "missing exposed face with normal " + axis);
        }
    }

    @Test
    void twoAdjacentBlocks_hideTheSharedInteriorFace_tenExposedFacesTotal() {
        BlockPos sectionOrigin = new BlockPos(0, 0, 0);
        BlockPos blockA = sectionOrigin.add(5, 5, 5);
        BlockPos blockB = sectionOrigin.add(6, 5, 5); // +X neighbor of blockA
        SectionChunks.SectionFixture fixture = SectionChunks.fixture(sectionOrigin);
        fillUniform(fixture, airState);
        fixture.setLocal(5, 5, 5, stoneState);
        fixture.setLocal(6, 5, 5, stoneState);
        WorldChunk chunk = fixture.chunk();

        List<Patch> patches = PatchAggregator.buildPatches(chunk, sectionOrigin);

        assertEquals(10, patches.size(), "2 blocks * 6 faces - 2 shared interior faces = 10");

        Patch hiddenFromA = new Patch(faceCentroid(blockA, new Vec3i(1, 0, 0)), new Vec3i(1, 0, 0), 1.0, STONE);
        Patch hiddenFromB = new Patch(faceCentroid(blockB, new Vec3i(-1, 0, 0)), new Vec3i(-1, 0, 0), 1.0, STONE);
        assertFalse(patches.contains(hiddenFromA), "shared interior +X face of blockA must not be exposed");
        assertFalse(patches.contains(hiddenFromB), "shared interior -X face of blockB must not be exposed");
    }

    @Test
    void allAirSection_producesNoPatches() {
        BlockPos sectionOrigin = new BlockPos(0, 0, 0);
        SectionChunks.SectionFixture fixture = SectionChunks.fixture(sectionOrigin);
        fillUniform(fixture, airState);
        WorldChunk chunk = fixture.chunk();

        List<Patch> patches = PatchAggregator.buildPatches(chunk, sectionOrigin);

        assertTrue(patches.isEmpty(), "no solid faces exist in an all-air section");
    }

    @Test
    void sectionEdgeFace_mustNotFabricatePatchesFromWrappedLocalCoordinates() {
        // WorldChunk masks local X/Z so x=-1 reads x=15 in the same chunk. PatchAggregator must
        // treat a neighbor outside the section as exposed (air), not as the wrapped far-side block.
        BlockPos sectionOrigin = new BlockPos(0, 0, 0);
        WorldChunk wrappingChunk = wrappingSectionChunk(sectionOrigin, stoneState);

        List<Patch> patches = PatchAggregator.buildPatches(wrappingChunk, sectionOrigin);

        Patch expectedWestFace = new Patch(faceCentroid(sectionOrigin, new Vec3i(-1, 0, 0)), new Vec3i(-1, 0, 0), 1.0, STONE);
        assertTrue(patches.contains(expectedWestFace),
                "block at local (0,0,0) must expose its -X face to out-of-section air; wrapping x=-1→x=15 must not hide it");
    }

    @Test
    void solidStateNonOpaqueBlock_stillContributesPatches() {
        assertFalse(glassState.isOpaque(), "fixture sanity: glass is not opaque");
        assertEquals(1.0, GLASS.state(), 1e-9, "glass material is solid by Material.state");

        BlockPos sectionOrigin = new BlockPos(0, 0, 0);
        BlockPos block = sectionOrigin.add(5, 5, 5);
        SectionChunks.SectionFixture fixture = SectionChunks.fixture(sectionOrigin);
        fillUniform(fixture, airState);
        fixture.setLocal(5, 5, 5, glassState);
        WorldChunk chunk = fixture.chunk();

        List<Patch> patches = PatchAggregator.buildPatches(chunk, sectionOrigin);

        assertEquals(6, patches.size(), "solid-state glass must contribute all 6 faces despite !isOpaque()");
        for (Vec3i axis : AXES) {
            Patch expected = new Patch(faceCentroid(block, axis), axis, 1.0, GLASS);
            assertTrue(patches.contains(expected), "missing glass face with normal " + axis);
        }
    }

    @Test
    void gasStateMaterial_doesNotContributePatches() {
        assertEquals(0.5, GAS.state(), 1e-9);
        // Air block with gas-state material (same as published AIR) — already covered by all-air,
        // but assert explicitly that state < solid threshold means no patches from a lone cell.
        BlockPos sectionOrigin = new BlockPos(0, 0, 0);
        SectionChunks.SectionFixture fixture = SectionChunks.fixture(sectionOrigin);
        fillUniform(fixture, airState);
        fixture.setLocal(5, 5, 5, airState);
        WorldChunk chunk = fixture.chunk();

        List<Patch> patches = PatchAggregator.buildPatches(chunk, sectionOrigin);
        assertTrue(patches.isEmpty(), "gas/air Material.state must not contribute acoustic patches");
    }

    /**
     * Mockito chunk that mimics {@code WorldChunk.getBlockState}'s local X/Z mask: out-of-range
     * local coords wrap into {@code [0,16)} instead of resolving as out-of-section air.
     */
    private static WorldChunk wrappingSectionChunk(BlockPos sectionOrigin, BlockState fill) {
        WorldChunk chunk = org.mockito.Mockito.mock(WorldChunk.class);
        org.mockito.Mockito.when(chunk.getBlockState(org.mockito.Mockito.any())).thenAnswer(invocation -> {
            BlockPos pos = invocation.getArgument(0);
            int x = Math.floorMod(pos.getX() - sectionOrigin.getX(), 16);
            int y = pos.getY() - sectionOrigin.getY();
            int z = Math.floorMod(pos.getZ() - sectionOrigin.getZ(), 16);
            if (y < 0 || y >= 16) {
                return airState;
            }
            return fill;
        });
        return chunk;
    }
}
