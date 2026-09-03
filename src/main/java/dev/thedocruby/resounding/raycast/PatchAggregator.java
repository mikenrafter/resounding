package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.MaterialRegistry;
import dev.thedocruby.resounding.material.Material;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2 acoustic patch aggregation pass (frustums-plan.md "Acoustic patch aggregation pass"): a
 * pass over exposed block faces, independent of {@code OctreeManager.growOctree}'s material-octree
 * build, run at the same trigger points ({@code OctreeManager.plantOctree} /
 * {@code scheduleReplant} / {@code replantLoadedSections}) but a separate, differently-shaped pass
 * built solely for acoustics — not reusing the chunk mesher.
 *
 * <p>Mirrors {@code OctreeManager.growOctree(WorldChunk, Branch)}'s signature shape
 * ({@code WorldChunk} + section origin) so it can be exercised against the same
 * {@code fixture.SectionChunks} Mockito fixture used for {@code growOctree} bake tests.
 */
public final class PatchAggregator {
    private PatchAggregator() {}

    /** The 6 axis-unit face directions, same convention as {@link Step#plane()}. */
    private static final Vec3i[] FACE_NORMALS = {
            new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0),
            new Vec3i(0, 1, 0), new Vec3i(0, -1, 0),
            new Vec3i(0, 0, 1), new Vec3i(0, 0, -1),
    };

    /**
     * Builds the patch list for the 16³ section rooted at {@code sectionOrigin}: one {@link Patch}
     * per exposed block face (a face is exposed when the block immediately beyond it is non-solid,
     * e.g. air), tagging normal, centroid, area, and material (reusing
     * {@code MaterialRegistry}/{@code Material} lookups already used by
     * {@code OctreeManager.growOctree}). Built once per chunk-section load/invalidate, not per
     * sound (frustums-plan.md Phase 2).
     *
     * <p>Solidity is determined by {@link BlockState#isOpaque()} — a context-free, cached property
     * of the block (no {@code BlockView}/collision-shape lookup needed), so it behaves correctly
     * even when queried right at a section boundary (the neighbor lookup may land outside the
     * fixture/section and simply resolve to whatever {@code WorldChunk#getBlockState} returns
     * there, typically air).
     */
    public static @NotNull List<Patch> buildPatches(@NotNull WorldChunk chunk, @NotNull BlockPos sectionOrigin) {
        List<Patch> patches = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockPos block = sectionOrigin.add(x, y, z);
                    BlockState state = chunk.getBlockState(block);
                    if (!state.isOpaque()) continue;

                    Material material = MaterialRegistry.material(state);
                    for (Vec3i normal : FACE_NORMALS) {
                        BlockPos neighborPos = block.add(normal);
                        BlockState neighborState = chunk.getBlockState(neighborPos);
                        if (neighborState.isOpaque()) continue;

                        Vec3d centroid = new Vec3d(
                                block.getX() + 0.5 + normal.getX() * 0.5,
                                block.getY() + 0.5 + normal.getY() * 0.5,
                                block.getZ() + 0.5 + normal.getZ() * 0.5
                        );
                        patches.add(new Patch(centroid, normal, 1.0, material));
                    }
                }
            }
        }
        return patches;
    }
}
