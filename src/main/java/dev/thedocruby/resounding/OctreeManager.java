package dev.thedocruby.resounding;

import dev.thedocruby.resounding.raycast.Branch;
import dev.thedocruby.resounding.toolbox.ChunkChain;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages the per-chunk octree spatial index used for fast block-material lookups during raycasting.
 *
 * Octrees are built per chunk section: plantOctree queues a section for indexing,
 * growOctree recursively subdivides the 16x16x16 section into an octree whose leaves
 * store the acoustic Material for that region. Homogeneous regions collapse into a
 * single node (the "set" optimization in Branch).
 */
public class OctreeManager {
    private OctreeManager() {}

    public final static VoxelShape EMPTY = VoxelShapes.empty();
    public final static VoxelShape CUBE = VoxelShapes.fullCube();

    public final static ExecutorService octreePool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    public static final BlockPos[] branchSequence = {
            new BlockPos(1, 0, 0),
            new BlockPos(0, 1, 0),
            new BlockPos(1, 1, 0),
            new BlockPos(0, 0, 1),
            new BlockPos(1, 0, 1),
            new BlockPos(0, 1, 1),
            new BlockPos(1, 1, 1)
    };
    public static final BlockPos[] blockSequence = ArrayUtils.addFirst(branchSequence, new BlockPos(0, 0, 0));

    public static long counter = 0;

    public static void plantOctree(ChunkChain chunk, int index, Branch root) {
        if (chunk == null) return; // handle unloaded chunks

        growOctree((WorldChunk) chunk, root); // mutates root
        chunk.set(index, root); // "plant" the "grown" octree
    }

    public static Branch growOctree(WorldChunk chunk, Branch root) {
        final int scale = root.size >> 1;
        final BlockPos start = root.start;
        // get first state at root position
        BlockState state = chunk.getBlockState(start);
        root.material = MaterialRegistry.material(state);
        boolean valid = true;

        if (scale > 1) {
            boolean any = false;
            for (BlockPos block : blockSequence) {
                final BlockPos position = start.add(block.multiply(scale));
                // use recursion here
                Branch leaf = growOctree(chunk, new Branch(position, scale, (Material) null));
                if (leaf.material == null) any = any || !leaf.isEmpty();
                else {
                    if (!root.material.equals(leaf.material)) {
                        any = true;
                        valid = false;
                    }
                }
                // don't break here, as understanding adjacent sections is important
                root.put(position.asLong(), leaf);
            }
            if (!any) root.empty();
            // for single-blocks
        } else {
            for (BlockPos block : branchSequence) {
                final BlockPos position = start.add(block);
                @NotNull Material next = MaterialRegistry.material(chunk.getBlockState(position));
                if (!root.material.equals(next)) {
                    valid = false;
                    break;
                }
            }
        }
        root.set(valid ? root.material : (Material) null);
        return root;
    }
}
