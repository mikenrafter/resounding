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
     * Material.state at or above this value counts as acoustically solid for patch contribution
     * (1.0 = solid; gas 0.5 and below do not contribute).
     */
    private static final double SOLID_STATE_THRESHOLD = 1.0;

    /**
     * Builds the patch list for the 16³ section rooted at {@code sectionOrigin}: one {@link Patch}
     * per exposed block face (a face is exposed when the block immediately beyond it is non-solid,
     * e.g. air), tagging normal, centroid, area, and material (reusing
     * {@code MaterialRegistry}/{@code Material} lookups already used by
     * {@code OctreeManager.growOctree}). Built once per chunk-section load/invalidate, not per
     * sound (frustums-plan.md Phase 2).
     *
     * <p>Solidity uses {@link Material#state()} against {@link #SOLID_STATE_THRESHOLD} rather than
     * {@link BlockState#isOpaque()}, so non-opaque solids (e.g. glass) still contribute. Neighbor
     * positions outside the 16³ section are treated as exposed air without consulting
     * {@code WorldChunk#getBlockState} (which can wrap local X/Z).
     */
    public static @NotNull List<Patch> buildPatches(@NotNull WorldChunk chunk, @NotNull BlockPos sectionOrigin) {
        List<Patch> patches = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockPos block = sectionOrigin.add(x, y, z);
                    BlockState state = chunk.getBlockState(block);
                    Material material = MaterialRegistry.material(state);
                    if (!isAcousticallySolid(material)) continue;

                    for (Vec3i normal : FACE_NORMALS) {
                        int nx = x + normal.getX();
                        int ny = y + normal.getY();
                        int nz = z + normal.getZ();
                        if (inSection(nx, ny, nz)) {
                            BlockPos neighborPos = sectionOrigin.add(nx, ny, nz);
                            Material neighborMaterial = MaterialRegistry.material(chunk.getBlockState(neighborPos));
                            if (isAcousticallySolid(neighborMaterial)) continue;
                        }

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

    private static boolean inSection(int localX, int localY, int localZ) {
        return localX >= 0 && localX < 16
                && localY >= 0 && localY < 16
                && localZ >= 0 && localZ < 16;
    }

    private static boolean isAcousticallySolid(@NotNull Material material) {
        return material.state() >= SOLID_STATE_THRESHOLD;
    }
}
