package dev.thedocruby.resounding;

import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.raycast.Branch;
import dev.thedocruby.resounding.raycast.Polarization;
import dev.thedocruby.resounding.toolbox.ChunkChain;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static dev.thedocruby.resounding.Engine.mc;
import static dev.thedocruby.resounding.config.PrecomputedConfig.pConfig;

/**
 * Manages the per-chunk octree spatial index used for fast block-material lookups during raycasting.
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
    public static volatile int materialGeneration = 0;

    public static void onMaterialsPublished() {
        materialGeneration++;
        replantLoadedSections();
    }

    private static void replantLoadedSections() {
        if (mc == null || mc.world == null) {
            return;
        }
        ChunkPos center = mc.player == null ? new ChunkPos(0, 0) : mc.player.getChunkPos();
        int radius = mc.options.getViewDistance().getValue() + 1;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                var chunk = mc.world.getChunkManager().getWorldChunk(center.x + dx, center.z + dz);
                if (chunk instanceof ChunkChain chain) {
                    scheduleReplant(chain);
                }
            }
        }
    }

    /** Visible for republishing: queues octree rebuilds for every non-air section in a loaded chunk. */
    public static void scheduleReplant(ChunkChain chunk) {
        chunk.replantOctrees();
    }

    public static int leafCount(Branch root) {
        return countLeaves(root);
    }

    public static void plantOctree(ChunkChain chunk, int index, Branch root) {
        if (chunk == null) return;

        if (!MaterialRegistry.isPopulated()) {
            Utils.LOGGER.warn(
                    "Resounding: planting octree at {} before materials are published; section will look homogeneous",
                    root.start
            );
        }

        growOctree((WorldChunk) chunk, root);
        chunk.set(index, root);

        if (pConfig != null && pConfig.dLog) {
            Utils.LOGGER.info(
                    "Resounding: planted octree section index {} origin={} leaves={} materialRegistry={}",
                    index,
                    root.start,
                    countLeaves(root),
                    MaterialRegistry.size()
            );
        }
    }

    public static Branch growOctree(WorldChunk chunk, Branch root) {
        final BlockPos start = root.start;
        if (root.size == 1) {
            BlockState state = chunk.getBlockState(start);
            root.material = MaterialRegistry.material(state);
            root.materialLabel = MaterialRegistry.describe(state);
            bakeLeafDescriptor(root);
            return root;
        }

        final int scale = root.size >> 1;
        BlockState corner = chunk.getBlockState(start);
        root.material = MaterialRegistry.material(corner);
        root.materialLabel = MaterialRegistry.describe(corner);
        boolean valid = true;

        if (scale > 1) {
            boolean heterogeneous = false;
            Material siblingMaterial = null;
            Branch[] children = new Branch[blockSequence.length];
            for (int i = 0; i < blockSequence.length; i++) {
                final BlockPos block = blockSequence[i];
                final BlockPos position = start.add(block.multiply(scale));
                Branch leaf = growOctree(chunk, new Branch(position, scale, (Material) null));
                children[i] = leaf;
                if (leaf.material == null || !leaf.isEmpty()) {
                    heterogeneous = true;
                    valid = false;
                } else if (!sameAcousticCell(corner, chunk.getBlockState(position))) {
                    heterogeneous = true;
                    valid = false;
                } else if (siblingMaterial == null) {
                    siblingMaterial = leaf.material;
                } else if (!siblingMaterial.equals(leaf.material)) {
                    heterogeneous = true;
                    valid = false;
                }
                root.put(position.asLong(), leaf);
            }
            if (!heterogeneous) {
                root.empty();
            }
            bakeAggregateDescriptor(root, children);
        } else {
            Material[] cornerMaterials = new Material[blockSequence.length];
            BlockState[] cornerStates = new BlockState[blockSequence.length];
            for (int i = 0; i < blockSequence.length; i++) {
                final BlockPos position = start.add(blockSequence[i]);
                BlockState blockState = chunk.getBlockState(position);
                cornerStates[i] = blockState;
                cornerMaterials[i] = MaterialRegistry.material(blockState);
            }
            valid = regionHomogeneous(chunk, start, 2, corner);
            Polarization.Descriptor descriptor = Polarization.bakeOctant(cornerMaterials);
            root.maxImpedance = descriptor.maxImpedance();
            root.minImpedance = descriptor.minImpedance();
            root.avgImpedance = descriptor.avgImpedance();
            root.polar = descriptor.polar();
            root.blendCoefficient = descriptor.blendCoefficient();
            if (!valid) {
                root.empty();
                for (int i = 0; i < blockSequence.length; i++) {
                    final BlockPos position = start.add(blockSequence[i]);
                    Branch leaf = new Branch(position, 1, cornerMaterials[i]);
                    leaf.materialLabel = MaterialRegistry.describe(cornerStates[i]);
                    bakeLeafDescriptor(leaf);
                    root.put(position.asLong(), leaf);
                }
                root.material = null;
                root.materialLabel = null;
                return root;
            }
        }
        root.set(valid ? root.material : (Material) null);
        if (!valid) {
            root.materialLabel = null;
        }
        return root;
    }

    /** Size-1 leaf baked descriptor (frustums-plan.md Phase 0 table): no gradient, max=min=avg. */
    private static void bakeLeafDescriptor(Branch leaf) {
        double impedance = leaf.material != null ? leaf.material.impedance() : Double.NaN;
        leaf.maxImpedance = impedance;
        leaf.minImpedance = impedance;
        leaf.avgImpedance = impedance;
        leaf.polar = null;
        leaf.blendCoefficient = Double.NaN;
    }

    /**
     * Size&gt;2 baked-descriptor aggregation (frustums-plan.md Phase 0 table): high/low/avg are
     * the mean of the 8 children's own already-reduced high/low/avg values (one running pass, not
     * a second traversal); {@code polar} is the weighted average of the children's own {@code
     * polar} vectors, weighted by each child's own stiffness (how high-impedance-dominant that
     * child is, derived from its retained {@link Branch#blendCoefficient} and quantized to
     * quarters via {@link Polarization#stiffWeight}). Children with no gradient of their own
     * ({@code polar == null}) contribute no direction.
     */
    private static void bakeAggregateDescriptor(Branch root, Branch[] children) {
        double sumHigh = 0.0;
        double sumLow = 0.0;
        double sumAvg = 0.0;
        double sumBlend = 0.0;
        int blendCount = 0;
        List<Vec3d> childPolar = new ArrayList<>(children.length);
        List<Double> childWeight = new ArrayList<>(children.length);
        for (Branch child : children) {
            sumHigh += child.maxImpedance;
            sumLow += child.minImpedance;
            sumAvg += child.avgImpedance;
            if (child.polar != null) {
                double blend = child.blendCoefficient;
                if (Double.isNaN(blend)) {
                    double range = child.maxImpedance - child.minImpedance;
                    blend = range > 0 ? (child.avgImpedance - child.minImpedance) / range : 1.0;
                    blend = Math.max(0.0, Math.min(1.0, blend));
                }
                childPolar.add(child.polar);
                childWeight.add(Polarization.stiffWeight(blend));
                sumBlend += blend;
                blendCount++;
            }
        }
        root.maxImpedance = sumHigh / children.length;
        root.minImpedance = sumLow / children.length;
        root.avgImpedance = sumAvg / children.length;
        root.blendCoefficient = blendCount > 0 ? sumBlend / blendCount : Double.NaN;
        if (childPolar.isEmpty()) {
            root.polar = null;
        } else {
            double[] weights = new double[childWeight.size()];
            for (int i = 0; i < weights.length; i++) {
                weights[i] = childWeight.get(i);
            }
            root.polar = Polarization.combinePolar(childPolar.toArray(new Vec3d[0]), weights);
        }
    }

    /**
     * Two positions belong to the same octree cell when their baked materials match and, if both
     * fell back to {@link MaterialRegistry#DEFAULT}, their block types still match.
     */
    static boolean sameAcousticCell(BlockState reference, BlockState sample) {
        if (reference == sample) {
            return true;
        }
        if (reference.getBlock() == sample.getBlock()) {
            return true;
        }
        Material referenceMaterial = MaterialRegistry.material(reference);
        Material sampleMaterial = MaterialRegistry.material(sample);
        if (!referenceMaterial.equals(sampleMaterial)) {
            return false;
        }
        if (referenceMaterial.equals(MaterialRegistry.DEFAULT)) {
            return false;
        }
        return true;
    }

    private static boolean regionHomogeneous(WorldChunk chunk, BlockPos start, int size, BlockState reference) {
        for (int dx = 0; dx < size; dx++) {
            for (int dy = 0; dy < size; dy++) {
                for (int dz = 0; dz < size; dz++) {
                    BlockState state = chunk.getBlockState(start.add(dx, dy, dz));
                    if (!sameAcousticCell(reference, state)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Subdivides ancestors along {@code pos} and clears material on the touched 1³ leaf so
     * {@link Cast#getBlock} can fall through without collapsing whole sections to 1³ stepping.
     */
    public static void invalidateBlock(WorldChunk chunk, Branch section, BlockPos pos) {
        if (chunk == null || section == null) {
            return;
        }
        invalidatePath(chunk, section, pos);
    }

    static void invalidatePath(WorldChunk chunk, Branch node, BlockPos target) {
        if (node.size == 1) {
            node.material = null;
            node.materialLabel = null;
            return;
        }

        if (node.leaves.isEmpty()) {
            subdivide(chunk, node);
        }

        node.material = null;
        node.materialLabel = null;

        Branch child = node.leaves.get(childKey(node, target));
        if (child != null) {
            invalidatePath(chunk, child, target);
        }

        if (!node.leaves.isEmpty()) {
            Branch[] children = new Branch[blockSequence.length];
            for (int i = 0; i < blockSequence.length; i++) {
                BlockPos origin = node.start.add(blockSequence[i].multiply(node.size >> 1));
                Branch baked = node.leaves.get(origin.asLong());
                if (baked == null) {
                    return;
                }
                children[i] = baked;
            }
            bakeAggregateDescriptor(node, children);
        }
    }

    private static void subdivide(WorldChunk chunk, Branch node) {
        int half = node.size >> 1;
        Branch[] children = new Branch[blockSequence.length];
        for (int i = 0; i < blockSequence.length; i++) {
            BlockPos offset = blockSequence[i];
            BlockPos origin = node.start.add(offset.multiply(half));
            Branch child = new Branch(origin, half);
            if (half == 1) {
                BlockState state = chunk.getBlockState(origin);
                child.material = MaterialRegistry.material(state);
                child.materialLabel = MaterialRegistry.describe(state);
                bakeLeafDescriptor(child);
            } else {
                BlockState corner = chunk.getBlockState(origin);
                if (regionHomogeneous(chunk, origin, half, corner)) {
                    child.material = MaterialRegistry.material(corner);
                    child.materialLabel = MaterialRegistry.describe(corner);
                    bakeLeafDescriptor(child);
                } else {
                    growOctree(chunk, child);
                }
            }
            children[i] = child;
            node.put(origin.asLong(), child);
        }
        bakeAggregateDescriptor(node, children);
    }

    private static long childKey(Branch node, BlockPos target) {
        int half = node.size >> 1;
        int dx = target.getX() >= node.start.getX() + half ? half : 0;
        int dy = target.getY() >= node.start.getY() + half ? half : 0;
        int dz = target.getZ() >= node.start.getZ() + half ? half : 0;
        return node.start.add(dx, dy, dz).asLong();
    }

    private static int countLeaves(Branch node) {
        if (node.leaves.isEmpty()) {
            return 1;
        }
        int count = 0;
        for (Branch child : node.leaves.values()) {
            count += countLeaves(child);
        }
        return count;
    }
}
