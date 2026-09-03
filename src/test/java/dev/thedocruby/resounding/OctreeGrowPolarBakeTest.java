package dev.thedocruby.resounding;

import dev.thedocruby.resounding.fixture.SectionChunks;
import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.raycast.Branch;
import dev.thedocruby.resounding.raycast.Polarization;
import dev.thedocruby.resounding.tag.Ident;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 RED tests for {@link OctreeManager#growOctree}'s baked per-branch descriptor
 * ({@code maxImpedance}/{@code minImpedance}/{@code avgImpedance}/{@code polar} on {@link Branch}),
 * reached through the real bake pass rather than the pure {@code Polarization} functions directly
 * (which have their own dedicated, more exhaustive unit coverage in
 * {@code raycast/PolarizationBakeOctantTest}). See references/research/frustums-plan.md's Phase 0
 * "Baked per-branch descriptor" table.
 */
class OctreeGrowPolarBakeTest {

    private static final Material STONE = new Material(2700.0, 0.5, 1.0);
    private static final Material AIR = new Material(1.2, 1.0, 0.5);
    private static final Material GRASS = new Material(500.0, 0.8, 0.9);
    /** "X" of the 2-material axis-striped worked pattern - higher impedance, so it's G_high. */
    private static final Material GRANITE = new Material(3200.0, 0.3, 1.0);
    /** "O" of the 2-material axis-striped worked pattern - lower impedance, so it's G_low. */
    private static final Material DIORITE = new Material(1500.0, 0.4, 1.0);
    private static final Material ANDESITE = new Material(800.0, 0.45, 1.0);
    private static final Material TUFF = new Material(600.0, 0.5, 1.0);
    private static final Material COBBLESTONE = new Material(400.0, 0.55, 1.0);
    private static final Material SANDSTONE = new Material(200.0, 0.6, 1.0);

    private static BlockState stoneState;
    private static BlockState airState;
    private static BlockState grassState;
    private static BlockState graniteState;
    private static BlockState dioriteState;
    private static BlockState andesiteState;
    private static BlockState tuffState;
    private static BlockState cobblestoneState;
    private static BlockState sandstoneState;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        stoneState = Blocks.STONE.getDefaultState();
        airState = Blocks.AIR.getDefaultState();
        grassState = Blocks.GRASS_BLOCK.getDefaultState();
        graniteState = Blocks.GRANITE.getDefaultState();
        dioriteState = Blocks.DIORITE.getDefaultState();
        andesiteState = Blocks.ANDESITE.getDefaultState();
        tuffState = Blocks.TUFF.getDefaultState();
        cobblestoneState = Blocks.COBBLESTONE.getDefaultState();
        sandstoneState = Blocks.SANDSTONE.getDefaultState();
    }

    @BeforeEach
    void publishMaterials() {
        MaterialRegistry.publish(Map.ofEntries(
                Map.entry(Ident.parse("minecraft:stone"), STONE),
                Map.entry(Ident.parse("minecraft:air"), AIR),
                Map.entry(Ident.parse("minecraft:grass_block"), GRASS),
                Map.entry(Ident.parse("minecraft:granite"), GRANITE),
                Map.entry(Ident.parse("minecraft:diorite"), DIORITE),
                Map.entry(Ident.parse("minecraft:andesite"), ANDESITE),
                Map.entry(Ident.parse("minecraft:tuff"), TUFF),
                Map.entry(Ident.parse("minecraft:cobblestone"), COBBLESTONE),
                Map.entry(Ident.parse("minecraft:sandstone"), SANDSTONE)
        ));
    }

    @Test
    void sizeOneLeaf_maxMinAvgAllEqualTheSingleMaterial_noPolar() {
        BlockPos sectionOrigin = new BlockPos(0, 0, 0);
        SectionChunks.SectionFixture fixture = SectionChunks.fixture(sectionOrigin);
        fillUniform(fixture, stoneState);
        fixture.setLocal(3, 7, 3, grassState); // lone anomaly forces a real size-1 leaf here
        WorldChunk chunk = fixture.chunk();

        Branch root = new Branch(sectionOrigin, 16);
        OctreeManager.growOctree(chunk, root);

        Branch leaf = root.get(sectionOrigin.add(3, 7, 3));
        assertEquals(1, leaf.size);
        assertEquals(GRASS.impedance(), leaf.maxImpedance);
        assertEquals(GRASS.impedance(), leaf.minImpedance);
        assertEquals(GRASS.impedance(), leaf.avgImpedance);
        assertNull(leaf.polar, "a single-material leaf has no gradient");
    }

    @Test
    void sizeTwoOctant_underFourDistinctValues_axisStripedPattern_singleMaxMinAndPolar() {
        BlockPos sectionOrigin = new BlockPos(0, 0, 0);
        SectionChunks.SectionFixture fixture = SectionChunks.fixture(sectionOrigin);
        fillUniform(fixture, airState);
        fillCube(fixture, 0, 0, 0, 4, stoneState);
        // idx 0-7 blockSequence order: X,O,X,O,X,O,X,O (axis-striped, P = (-8,0,0), 50/50 blend)
        fixture.setLocal(0, 0, 0, graniteState);
        fixture.setLocal(1, 0, 0, dioriteState);
        fixture.setLocal(0, 1, 0, graniteState);
        fixture.setLocal(1, 1, 0, dioriteState);
        fixture.setLocal(0, 0, 1, graniteState);
        fixture.setLocal(1, 0, 1, dioriteState);
        fixture.setLocal(0, 1, 1, graniteState);
        fixture.setLocal(1, 1, 1, dioriteState);
        WorldChunk chunk = fixture.chunk();

        Branch root = new Branch(sectionOrigin, 16);
        OctreeManager.growOctree(chunk, root);

        Branch patch = descend(root, sectionOrigin, 2);
        assertEquals(2, patch.size, "the 2-material patch must survive as its own un-pruned size-2 branch");
        assertEquals(GRANITE.impedance(), patch.maxImpedance, "only 2 distinct values (<4): single max, not mean-of-top-4");
        assertEquals(DIORITE.impedance(), patch.minImpedance, "only 2 distinct values (<4): single min, not mean-of-bottom-4");
        assertEquals((GRANITE.impedance() + DIORITE.impedance()) / 2.0, patch.avgImpedance);
        assertEquals(new Vec3d(-8, 0, 0), patch.polar, "axis-striped pattern must polarize along X, matching the plan's worked example");
    }

    @Test
    void sizeTwoOctant_fourOrMoreDistinctValues_highIsMeanTop4LowIsMeanBottom4() {
        BlockPos sectionOrigin = new BlockPos(0, 0, 0);
        SectionChunks.SectionFixture fixture = SectionChunks.fixture(sectionOrigin);
        fillUniform(fixture, airState);
        fillCube(fixture, 8, 0, 0, 4, stoneState);
        // idx 0-7: A(800),B(600),C(400),D(200) repeated - 4 distinct values, so mean-of-top-4/bottom-4 applies.
        fixture.setLocal(8, 0, 0, andesiteState);
        fixture.setLocal(9, 0, 0, tuffState);
        fixture.setLocal(8, 1, 0, cobblestoneState);
        fixture.setLocal(9, 1, 0, sandstoneState);
        fixture.setLocal(8, 0, 1, andesiteState);
        fixture.setLocal(9, 0, 1, tuffState);
        fixture.setLocal(8, 1, 1, cobblestoneState);
        fixture.setLocal(9, 1, 1, sandstoneState);
        WorldChunk chunk = fixture.chunk();

        Branch root = new Branch(sectionOrigin, 16);
        OctreeManager.growOctree(chunk, root);

        Branch patch = descend(root, sectionOrigin.add(8, 0, 0), 2);
        assertEquals(2, patch.size, "the 4-material patch must survive as its own un-pruned size-2 branch");
        // corners by value: 800,800,600,600,400,400,200,200 -> top4 mean=700, bottom4 mean=300
        assertEquals(700.0, patch.maxImpedance);
        assertEquals(300.0, patch.minImpedance);
        assertEquals(500.0, patch.avgImpedance, "mean of all 8 corners regardless of the max/min branch");
    }

    @Test
    void sizeTwoBranch_retainsDescriptorBlendCoefficientForStiffWeight() {
        BlockPos sectionOrigin = new BlockPos(0, 0, 0);
        SectionChunks.SectionFixture fixture = SectionChunks.fixture(sectionOrigin);
        fillUniform(fixture, airState);
        // 7 granite : 1 diorite — blendCoefficient = 7/8 = 0.875
        fixture.setLocal(0, 0, 0, graniteState);
        fixture.setLocal(1, 0, 0, graniteState);
        fixture.setLocal(0, 1, 0, graniteState);
        fixture.setLocal(1, 1, 0, graniteState);
        fixture.setLocal(0, 0, 1, graniteState);
        fixture.setLocal(1, 0, 1, graniteState);
        fixture.setLocal(0, 1, 1, graniteState);
        fixture.setLocal(1, 1, 1, dioriteState);
        WorldChunk chunk = fixture.chunk();

        Branch root = new Branch(sectionOrigin, 16);
        OctreeManager.growOctree(chunk, root);

        Branch patch = descend(root, sectionOrigin, 2);
        assertEquals(2, patch.size);
        Material[] corners = {
                GRANITE, GRANITE, GRANITE, GRANITE, GRANITE, GRANITE, GRANITE, DIORITE
        };
        double expectedBlend = Polarization.bakeOctant(corners).blendCoefficient();
        assertEquals(0.875, expectedBlend, 1e-9);
        assertEquals(expectedBlend, patch.blendCoefficient, 1e-9,
                "bake must retain Descriptor.blendCoefficient on the Branch for stiff_weight");
    }

    @Test
    void sizeGreaterThanTwoAggregate_stiffWeightFollowsBlendCoefficientNotMidpointIdentity() {
        // Two adjacent size-2 children inside a size-4 parent:
        //  - high-dominant ≥4-distinct (blendCoefficient ≠ 0.5; impedance midpoint identity is 0.5)
        //  - balanced 4:4 stripe (blendCoefficient = 0.5)
        // Combined polar must weight the high-dominant child more — i.e. stiffWeight(blendCoefficient).
        BlockPos sectionOrigin = new BlockPos(0, 0, 0);
        SectionChunks.SectionFixture fixture = SectionChunks.fixture(sectionOrigin);
        fillUniform(fixture, airState);

        // Child A at (0,0,0) size 2: 5 andesite(800), 1 sandstone(200), 1 tuff(600), 1 cobble(400)
        // → 4 distinct, highCount=5, blend=5/6; polar biased by corner arrangement
        fixture.setLocal(0, 0, 0, andesiteState);
        fixture.setLocal(1, 0, 0, andesiteState);
        fixture.setLocal(0, 1, 0, andesiteState);
        fixture.setLocal(1, 1, 0, andesiteState);
        fixture.setLocal(0, 0, 1, andesiteState);
        fixture.setLocal(1, 0, 1, tuffState);
        fixture.setLocal(0, 1, 1, cobblestoneState);
        fixture.setLocal(1, 1, 1, sandstoneState);

        // Child B at (2,0,0) size 2: axis-striped granite/diorite → blend 0.5, polar (-8,0,0)
        fixture.setLocal(2, 0, 0, graniteState);
        fixture.setLocal(3, 0, 0, dioriteState);
        fixture.setLocal(2, 1, 0, graniteState);
        fixture.setLocal(3, 1, 0, dioriteState);
        fixture.setLocal(2, 0, 1, graniteState);
        fixture.setLocal(3, 0, 1, dioriteState);
        fixture.setLocal(2, 1, 1, graniteState);
        fixture.setLocal(3, 1, 1, dioriteState);

        // Fill remaining size-2 children of the (0,0,0) size-4 with homogeneous air so they contribute
        // no polar (null) and drop out of the weighted combine.
        WorldChunk chunk = fixture.chunk();
        Branch root = new Branch(sectionOrigin, 16);
        OctreeManager.growOctree(chunk, root);

        Branch size4 = descend(root, sectionOrigin, 4);
        Branch childA = descend(root, sectionOrigin, 2);
        Branch childB = descend(root, sectionOrigin.add(2, 0, 0), 2);

        assertEquals(4, size4.size);
        assertEquals(2, childA.size);
        assertEquals(2, childB.size);
        assertNotNull(childA.polar);
        assertNotNull(childB.polar);

        double blendA = Polarization.bakeOctant(new Material[]{
                ANDESITE, ANDESITE, ANDESITE, ANDESITE, ANDESITE, TUFF, COBBLESTONE, SANDSTONE
        }).blendCoefficient();
        double blendB = 0.5;
        assertTrue(Math.abs(blendA - 0.5) > 1e-6, "precondition: child A blend must differ from 0.5");

        // Impedance-range identity collapses ≥4-distinct children to weight 0.5; real stiff weight
        // must use blendCoefficient.
        double wrongA = Polarization.stiffWeight(
                (childA.avgImpedance - childA.minImpedance) / (childA.maxImpedance - childA.minImpedance));
        double rightA = Polarization.stiffWeight(blendA);
        assertTrue(Math.abs(wrongA - rightA) > 1e-6,
                "precondition: impedance midpoint identity must disagree with blendCoefficient stiff weight");

        Vec3d expected = Polarization.combinePolar(
                new Vec3d[]{ childA.polar, childB.polar },
                new double[]{ Polarization.stiffWeight(blendA), Polarization.stiffWeight(blendB) }
        );
        assertEquals(expected.x, size4.polar.x, 1e-6,
                "size>2 polar must weight children by stiffWeight(blendCoefficient), not the midpoint identity");
        assertEquals(expected.y, size4.polar.y, 1e-6);
        assertEquals(expected.z, size4.polar.z, 1e-6);
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

    private static void fillCube(SectionChunks.SectionFixture fixture, int ox, int oy, int oz, int size, BlockState state) {
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    fixture.setLocal(ox + x, oy + y, oz + z, state);
                }
            }
        }
    }

    /** Walks real (non-pruned) branches from {@code node} toward {@code pos}, stopping at {@code untilSize}. */
    private static Branch descend(Branch node, BlockPos pos, int untilSize) {
        Branch current = node;
        while (current.size > untilSize && !current.leaves.isEmpty()) {
            int half = current.size >> 1;
            int dx = pos.getX() >= current.start.getX() + half ? half : 0;
            int dy = pos.getY() >= current.start.getY() + half ? half : 0;
            int dz = pos.getZ() >= current.start.getZ() + half ? half : 0;
            BlockPos childOrigin = current.start.add(dx, dy, dz);
            Branch child = current.leaves.get(childOrigin.asLong());
            if (child == null) break;
            current = child;
        }
        return current;
    }
}
