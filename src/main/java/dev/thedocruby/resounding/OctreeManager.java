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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.thedocruby.resounding.Engine.mc;
import static dev.thedocruby.resounding.config.PrecomputedConfig.pConfig;

/**
 * Manages the per-chunk octree spatial index used for fast block-material lookups during raycasting.
 */
public class OctreeManager {
    private OctreeManager() {}

    public final static VoxelShape EMPTY = VoxelShapes.empty();
    public final static VoxelShape CUBE = VoxelShapes.fullCube();

    /** Named so sampling profilers (spark, async-profiler, JFR) show real thread identity instead of pool-N-thread-M. */
    private static final ThreadFactory OCTREE_THREAD_FACTORY = new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(@NotNull Runnable r) {
            Thread thread = new Thread(r, "resounding-octree-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    };
    public final static ExecutorService octreePool =
            Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), OCTREE_THREAD_FACTORY);

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

    private static volatile OctreePlantScheduler plantScheduler;

    private static OctreePlantScheduler plantScheduler() {
        OctreePlantScheduler existing = plantScheduler;
        if (existing != null) {
            return existing;
        }
        synchronized (OctreeManager.class) {
            if (plantScheduler == null) {
                plantScheduler = new OctreePlantScheduler(
                        System::currentTimeMillis,
                        OctreeManager::playerChunkPos,
                        OctreeManager::plantRingRadius,
                        ChunkGenerationActivity::nearGeneration,
                        octreePool,
                        OctreeManager::runPlantJob
                );
            }
            return plantScheduler;
        }
    }

    private static @Nullable ChunkPos playerChunkPos() {
        if (mc == null || mc.player == null) {
            return null;
        }
        return mc.player.getChunkPos();
    }

    private static int plantRingRadius() {
        if (pConfig == null) {
            return -1;
        }
        return pConfig.soundSimulationDistance + OctreePlantScheduler.RING_BUFFER_CHUNKS;
    }

    private static void runPlantJob(OctreePlantScheduler.PlantJob job) {
        if (job.materialGeneration() != materialGeneration) {
            return;
        }
        plantOctree(job.chunk(), job.sectionIndex(), job.root());
    }

    /** Call from chunk {@code initStorage} so distant deferred plants wait for quiet. */
    public static void noteChunkLoad() {
        plantScheduler().noteChunkLoad();
    }

    /**
     * Priority if within soundSimulationDistance+2 of the player (ignores 2s quiet);
     * otherwise deferred until quiet. See {@code CHUNK_PLANT_DEBOUNCE.md}.
     */
    public static void schedulePlant(ChunkChain chunk, ChunkPos pos, int index, Branch root) {
        plantScheduler().schedule(new OctreePlantScheduler.PlantJob(
                pos, index, materialGeneration, chunk, root
        ));
    }

    public static void onClientTick() {
        plantScheduler().onClientTick();
    }

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
            // materialLabel filled lazily by overlay / dRays via Branch.ensureMaterialLabel
            bakeLeafDescriptor(root);
            return root;
        }

        final int scale = root.size >> 1;
        BlockState corner = chunk.getBlockState(start);
        root.material = MaterialRegistry.material(corner);
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
                root.put(i, leaf);
            }
            if (!heterogeneous) {
                // Pruned to one homogeneous material: bake a plain leaf descriptor, not the
                // aggregate. sameAcousticCell's block-identity shortcut (this loop's own
                // heterogeneity check) can call two corners "the same" without ever comparing
                // their actual impedance, so bakeAggregateDescriptor's weighted-average polar
                // could otherwise carry a stale nonzero gradient onto a node the tree itself just
                // decided has no gradient at all.
                root.empty();
                bakeLeafDescriptor(root);
            } else {
                bakeAggregateDescriptor(root, children);
            }
        } else {
            Material[] cornerMaterials = new Material[blockSequence.length];
            for (int i = 0; i < blockSequence.length; i++) {
                final BlockPos position = start.add(blockSequence[i]);
                cornerMaterials[i] = MaterialRegistry.material(chunk.getBlockState(position));
            }
            valid = regionHomogeneous(chunk, start, 2, corner);
            if (valid) {
                // Same reasoning as the scale>1 branch above: regionHomogeneous's sameAcousticCell
                // shortcut can prune this node to one material without its corners' actual
                // impedances agreeing, so Polarization.bakeOctant's descriptor (and its polar)
                // must not be used here -- bake the plain single-material descriptor instead.
                bakeLeafDescriptor(root);
            } else {
                Polarization.Descriptor descriptor = Polarization.bakeOctant(cornerMaterials);
                root.bake(new Branch.NodeDescriptor(
                        descriptor.mostCommonImpedance(),
                        descriptor.leastCommonImpedance(),
                        descriptor.avgImpedance(),
                        descriptor.blendCoefficient(),
                        descriptor.polar()
                ));
                root.empty();
                for (int i = 0; i < blockSequence.length; i++) {
                    final BlockPos position = start.add(blockSequence[i]);
                    Branch leaf = new Branch(position, 1, cornerMaterials[i]);
                    bakeLeafDescriptor(leaf);
                    root.put(i, leaf);
                }
                root.material = null;
                return root;
            }
        }
        root.set(valid ? root.material : (Material) null);
        return root;
    }

    /** Size-1 leaf baked descriptor (frustums-plan.md Phase 0 table): no gradient, max=min=avg. */
    private static void bakeLeafDescriptor(Branch leaf) {
        double impedance = leaf.material != null ? leaf.material.impedance() : Double.NaN;
        leaf.bake(new Branch.NodeDescriptor(impedance, impedance, impedance, Double.NaN, null));
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
            // Snapshot once: child.descriptor is a single volatile read, so every field below
            // comes from the same baked generation instead of possibly straddling two.
            Branch.NodeDescriptor d = child.descriptor;
            sumHigh += d.mostCommonImpedance();
            sumLow += d.leastCommonImpedance();
            sumAvg += d.avgImpedance();
            if (d.polar() != null) {
                double blend = d.blendCoefficient();
                if (Double.isNaN(blend)) {
                    double range = Math.abs(d.mostCommonImpedance() - d.leastCommonImpedance());
                    blend = range > 0
                            ? Math.abs(d.avgImpedance() - d.leastCommonImpedance()) / range
                            : 1.0;
                    blend = Math.max(0.0, Math.min(1.0, blend));
                }
                childPolar.add(d.polar());
                childWeight.add(Polarization.stiffWeight(blend));
                sumBlend += blend;
                blendCount++;
            }
        }
        Vec3d combinedPolar;
        if (childPolar.isEmpty()) {
            combinedPolar = null;
        } else {
            double[] weights = new double[childWeight.size()];
            for (int i = 0; i < weights.length; i++) {
                weights[i] = childWeight.get(i);
            }
            combinedPolar = Polarization.combinePolar(childPolar.toArray(new Vec3d[0]), weights);
        }
        root.bake(new Branch.NodeDescriptor(
                sumHigh / children.length,
                sumLow / children.length,
                sumAvg / children.length,
                blendCount > 0 ? sumBlend / blendCount : Double.NaN,
                combinedPolar
        ));
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

        if (node.isEmpty()) {
            subdivide(chunk, node);
        }

        node.material = null;
        node.materialLabel = null;

        Branch child = node.child(node.octantOf(target));
        if (child != null) {
            invalidatePath(chunk, child, target);
        }

        if (!node.isEmpty()) {
            Branch[] children = new Branch[blockSequence.length];
            for (int i = 0; i < blockSequence.length; i++) {
                Branch baked = node.child(i);
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
                bakeLeafDescriptor(child);
            } else {
                BlockState corner = chunk.getBlockState(origin);
                if (regionHomogeneous(chunk, origin, half, corner)) {
                    child.material = MaterialRegistry.material(corner);
                    bakeLeafDescriptor(child);
                } else {
                    growOctree(chunk, child);
                }
            }
            children[i] = child;
            node.put(i, child);
        }
        bakeAggregateDescriptor(node, children);
    }

    private static int countLeaves(Branch node) {
        if (node.isEmpty()) {
            return 1;
        }
        int count = 0;
        for (Branch child : node.children) {
            if (child != null) {
                count += countLeaves(child);
            }
        }
        return count;
    }
}
