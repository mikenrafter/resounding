package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.OctreeManager;
import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.toolbox.ChunkChain;
import dev.thedocruby.resounding.toolbox.MaterialData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.shape.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;

@Environment(EnvType.CLIENT)
public class Branch {
    public BlockPos start;
    public int size;
    public @NotNull VoxelShape shape = OctreeManager.CUBE;
    public @Nullable Material material; // TODO: use!
    public @Nullable String materialLabel;

    // Phase 0 baked descriptor (frustums-plan.md "Baked per-branch descriptor"). Populated by
    // OctreeManager.growOctree's post-order bake pass; NaN/null until that lands (Phase 0 GREEN).
    /** Presence-split primary endpoint ({@code g_most}) — most-common material's adjusted impedance. */
    public double mostCommonImpedance = Double.NaN;
    /** Presence-split secondary endpoint ({@code g_least}) — least-common material's adjusted impedance. */
    public double leastCommonImpedance = Double.NaN;
    /** Mean impedance across this octant (leaf-level: same as most/least; internal: mean of children). */
    public double avgImpedance = Double.NaN;
    /**
     * Raw corner-sign-sum polarization vector — the same primitive as Phase 0.5's {@code P},
     * unnormalized. {@code null} means "no gradient" (octant size 1, or a fully homogeneous
     * octant), distinct from a real {@code (0,0,0)} cancellation (see the checkerboard case in
     * the plan). Never renormalized after the size&gt;2 weighted average combine, consistent with
     * staying a "polar" rather than a unit normal.
     */
    public @Nullable Vec3d polar;
    /**
     * Phase 0.5 blend coefficient retained from {@link Polarization.Descriptor#blendCoefficient()}
     * so size&gt;2 aggregation can feed {@link Polarization#stiffWeight(double)} the real presence
     * dominance, not an impedance-range reconstruction. {@link Double#NaN} until baked.
     */
    public double blendCoefficient = Double.NaN;

    public @NotNull HashMap<Long, Branch> leaves;


    public Branch(BlockPos start, int size) {
        this.leaves = new HashMap<>(size < 4 ? 0 : 8, 2 /* should never be reached */);
        this.start = start;
        this.size = size;
    }

    public Branch(BlockPos start, int size, @NotNull VoxelShape shape) {
        this(start, size);
        set(shape);
    }

    public Branch(BlockPos start, int size, @Nullable Material material) {
        this(start, size);
        set(material);
    }

    public Branch(BlockPos start, int size, @Nullable VoxelShape shape, @Nullable Material material) {
        this(start, size);
        set(shape);
        set(material);
    }

    public Branch set(@Nullable VoxelShape shape) {
        this.shape = shape;
        return this;
    }

    public Branch set(@Nullable Material material) {
        this.material = material;
        return this;
    }

    public Branch set(int size) {
        this.size = size;
        return this;
    }

    public @NotNull Branch get(BlockPos pos) {
        if (leaves.isEmpty()) return this;
        int half = size >> 1;
        if (half == 0) return this;
        int dx = pos.getX() >= start.getX() + half ? half : 0;
        int dy = pos.getY() >= start.getY() + half ? half : 0;
        int dz = pos.getZ() >= start.getZ() + half ? half : 0;
        BlockPos childOrigin = start.add(dx, dy, dz);
        @Nullable Branch leaf = leaves.get(childOrigin.asLong());
        return leaf == null ? this : leaf.get(pos);
    }

    /**
     * Resolves the octree at frustum LOD {@code lodSize}: returns the real node whose {@code size}
     * equals {@code lodSize} (even if it still has children — that node's baked polar/avg is the
     * LOD aggregate), or a virtual same-size cell carved from a coarser homogeneous leaf.
     */
    public @NotNull Branch getAtLod(@NotNull BlockPos pos, int lodSize) {
        int lod = Math.max(1, lodSize);
        if (size < lod) {
            return this;
        }
        if (size == lod) {
            return this;
        }
        if (leaves.isEmpty()) {
            return virtualLodCell(pos, lod);
        }
        int half = size >> 1;
        if (half == 0) {
            return this;
        }
        int dx = pos.getX() >= start.getX() + half ? half : 0;
        int dy = pos.getY() >= start.getY() + half ? half : 0;
        int dz = pos.getZ() >= start.getZ() + half ? half : 0;
        BlockPos childOrigin = start.add(dx, dy, dz);
        Branch child = leaves.get(childOrigin.asLong());
        if (child == null) {
            return virtualLodCell(pos, lod);
        }
        return child.getAtLod(pos, lod);
    }

    private @NotNull Branch virtualLodCell(@NotNull BlockPos pos, int lodSize) {
        BlockPos origin = FrustumLod.alignOrigin(pos, start, lodSize);
        // Keep the virtual cell inside this node's bounds.
        int maxX = start.getX() + size - lodSize;
        int maxY = start.getY() + size - lodSize;
        int maxZ = start.getZ() + size - lodSize;
        int ox = Math.min(Math.max(origin.getX(), start.getX()), Math.max(start.getX(), maxX));
        int oy = Math.min(Math.max(origin.getY(), start.getY()), Math.max(start.getY(), maxY));
        int oz = Math.min(Math.max(origin.getZ(), start.getZ()), Math.max(start.getZ(), maxZ));
        Branch virtual = new Branch(new BlockPos(ox, oy, oz), lodSize, material);
        virtual.materialLabel = materialLabel;
        virtual.mostCommonImpedance = mostCommonImpedance;
        virtual.leastCommonImpedance = leastCommonImpedance;
        virtual.avgImpedance = avgImpedance;
        virtual.polar = polar;
        virtual.blendCoefficient = blendCoefficient;
        virtual.shape = shape;
        return virtual;
    }

    // recursively search tree for corresponding branch
    // positions are normalized by section (16³)
    @Deprecated
    public @NotNull Branch get(BlockPos pos, int layer) {
        return get(pos);
    }

//    private static BlockPos sub(BlockPos pos, BlockPos octo, int n) {
//        return pos.subtract(octo.multiply(n));
//    }

    // this should only be used
    @Deprecated // NOT REALLY, but @Unsafe isn't available... :/
    public Branch put(Long pos, Branch branch) { return leaves.put(pos, branch); }

    public Branch empty() {
        leaves.clear();
        return this;
    }

    public boolean isEmpty() {
        return leaves.isEmpty();
    }

    public Branch replace(Long pos, Branch branch) { return leaves.replace(pos, branch); }

    /**
     * Phase 0 same-size neighbor accessor (frustums-plan.md "Neighbor accessor"): returns a
     * virtual {@link Branch} of this branch's own {@code size}, representing whatever occupies
     * the region immediately adjacent along {@code direction} (one of the 6 axis-unit vectors),
     * even when the neighbor's real tree resolution differs.
     * <p>Resolution:
     * <ol>
     *   <li>shift {@code start} by {@code size} along the crossed axis;</li>
     *   <li>if the shifted box leaves this octant's chunk/section, hand off to
     *       {@code chunk.access}/{@code getBranch} to find the right root;</li>
     *   <li>walk down from that root toward the shifted box, stopping at the requested size or at
     *       whatever coarser (pruned/homogeneous) branch is found first;</li>
     *   <li><b>coarser</b>: trivial replication — max = min = avg = that material's impedance,
     *       {@code polar = null} (no gradient);</li>
     *   <li><b>at/finer</b>: return the real pre-baked {@link Branch} directly, no aggregation.</li>
     * </ol>
     */
    public @NotNull Branch neighbor(@NotNull Vec3i direction, @Nullable ChunkChain chunk) {
        BlockPos shiftedStart = start.add(
                direction.getX() * size,
                direction.getY() * size,
                direction.getZ() * size
        );

        int chunkX = shiftedStart.getX() >> 4;
        int chunkZ = shiftedStart.getZ() >> 4;
        int ySection = shiftedStart.getY() >> 4;

        ChunkChain targetChunk = chunk.access(chunkX, chunkZ);
        Branch sectionRoot = targetChunk.getBranch(ySection);

        Branch current = sectionRoot;
        while (current.size > this.size && !current.leaves.isEmpty()) {
            int half = current.size >> 1;
            int dx = shiftedStart.getX() >= current.start.getX() + half ? half : 0;
            int dy = shiftedStart.getY() >= current.start.getY() + half ? half : 0;
            int dz = shiftedStart.getZ() >= current.start.getZ() + half ? half : 0;
            BlockPos childOrigin = current.start.add(dx, dy, dz);
            Branch child = current.leaves.get(childOrigin.asLong());
            if (child == null) break;
            current = child;
        }

        if (current.size == this.size) {
            // at or finer than the requested size: already-baked descriptor, no aggregation.
            return current;
        }

        // coarser pruned/homogeneous ancestor found first: trivial replication, positioned at
        // the accessor's own size within the larger uniform space.
        Branch virtual = new Branch(shiftedStart, this.size, current.material);
        virtual.materialLabel = current.materialLabel;
        if (current.material != null) {
            double impedance = current.material.impedance();
            virtual.mostCommonImpedance = impedance;
            virtual.leastCommonImpedance = impedance;
            virtual.avgImpedance = impedance;
        }
        virtual.polar = null;
        return virtual;
    }

    /**
     * Phase 0.5 edge-neighbor arithmetic decision (frustums-plan.md "Neighbor resolution for the
     * interaction: edges, not vertices"): {@code start % (size*2) == 0} on the axis matching
     * {@code direction} means this octant is the "low" child there. Stepping further negative
     * from a low child (or further positive from a high child) exits the immediate parent, which
     * is exactly the cross-parent case that must fall through to {@link #neighbor}. Not yet
     * implemented — always throws.
     */
    public boolean crossesParentBoundary(@NotNull Vec3i direction) {
        return axisCrossesParentBoundary(start.getX(), direction.getX())
                || axisCrossesParentBoundary(start.getY(), direction.getY())
                || axisCrossesParentBoundary(start.getZ(), direction.getZ());
    }

    /** {@code start % (size*2) == 0} on this axis -> low child; low crosses only on a negative
     * step, high only on a positive one. A zero step on this axis never crosses. */
    private boolean axisCrossesParentBoundary(int axisStart, int axisDirection) {
        if (axisDirection == 0) return false;
        boolean low = Math.floorMod(axisStart, size * 2) == 0;
        return low ? axisDirection < 0 : axisDirection > 0;
    }

    /**
     * Phase 0.5 edge-neighbor resolution: the 3 other octants sharing the edge nearest the face
     * hit in {@code faceDirection} (a 3D edge, like a 2D corner, is shared by exactly 4 cells —
     * a pinwheel of 4 cubes, so still exactly 3 other octants). {@code parent} is the immediate
     * parent branch already in hand; when {@link #crossesParentBoundary} is false for the
     * relevant axes the 3 neighbors are free sibling lookups via {@code parent.leaves} (no
     * traversal), otherwise this falls through to {@link #neighbor}. Not yet implemented — always
     * throws.
     */
    public @NotNull Branch[] edgeNeighbors(@NotNull Vec3i faceDirection, @Nullable Branch parent, @Nullable ChunkChain chunk) {
        // faceDirection is a single axis-unit vector (per contract, mirrors Branch.neighbor's own
        // direction contract). The edge nearest the hit face runs along the one remaining axis not
        // involved in the pinwheel; pick the "other" axis cyclically (X->Y->Z->X) and walk it
        // toward this octant's own parent-interior side (nearest edge = center-ward, not the outer
        // world boundary), matching "walk to the nearest edge of the face" from the plan.
        int faceAxis = faceDirection.getX() != 0 ? 0 : faceDirection.getY() != 0 ? 1 : 2;
        int otherAxis = (faceAxis + 1) % 3;

        Vec3i otherDirection = axisUnit(otherAxis, interiorStep(otherAxis));
        Vec3i diagonalDirection = new Vec3i(
                faceDirection.getX() + otherDirection.getX(),
                faceDirection.getY() + otherDirection.getY(),
                faceDirection.getZ() + otherDirection.getZ()
        );

        return new Branch[]{
                resolveEdgeNeighbor(faceDirection, parent, chunk),
                resolveEdgeNeighbor(otherDirection, parent, chunk),
                resolveEdgeNeighbor(diagonalDirection, parent, chunk)
        };
    }

    /**
     * Hit-point-aware edge-neighbor resolution (frustums-plan.md: walk to the nearest edge of the
     * hit face relative to the beam hit).
     */
    public @NotNull Branch[] edgeNeighbors(
            @NotNull Vec3i faceDirection,
            @Nullable Branch parent,
            @Nullable ChunkChain chunk,
            @NotNull Vec3d hitPoint
    ) {
        int faceAxis = faceDirection.getX() != 0 ? 0 : faceDirection.getY() != 0 ? 1 : 2;
        int axisA = (faceAxis + 1) % 3;
        int axisB = (faceAxis + 2) % 3;

        double faceMinA = axisCoord(start, axisA);
        double faceMaxA = faceMinA + size;
        double faceMinB = axisCoord(start, axisB);
        double faceMaxB = faceMinB + size;
        double hitA = axisCoord(hitPoint, axisA);
        double hitB = axisCoord(hitPoint, axisB);

        // Four edges of the hit face; pick the nearest in the face plane.
        double distPosA = Math.abs(hitA - faceMaxA);
        double distNegA = Math.abs(hitA - faceMinA);
        double distPosB = Math.abs(hitB - faceMaxB);
        double distNegB = Math.abs(hitB - faceMinB);

        int edgeAxis;
        int edgeSign;
        double best = distPosA;
        edgeAxis = axisA;
        edgeSign = 1;
        if (distNegA < best) {
            best = distNegA;
            edgeSign = -1;
        }
        if (distPosB < best) {
            best = distPosB;
            edgeAxis = axisB;
            edgeSign = 1;
        }
        if (distNegB < best) {
            edgeAxis = axisB;
            edgeSign = -1;
        }

        Vec3i edgeDirection = axisUnit(edgeAxis, edgeSign);
        Vec3i diagonalDirection = new Vec3i(
                faceDirection.getX() + edgeDirection.getX(),
                faceDirection.getY() + edgeDirection.getY(),
                faceDirection.getZ() + edgeDirection.getZ()
        );

        return new Branch[]{
                resolveEdgeNeighbor(faceDirection, parent, chunk),
                resolveEdgeNeighbor(edgeDirection, parent, chunk),
                resolveEdgeNeighbor(diagonalDirection, parent, chunk)
        };
    }

    private static double axisCoord(BlockPos pos, int axis) {
        return axis == 0 ? pos.getX() : axis == 1 ? pos.getY() : pos.getZ();
    }

    private static double axisCoord(Vec3d pos, int axis) {
        return axis == 0 ? pos.x : axis == 1 ? pos.y : pos.z;
    }

    /** Which of this branch's own siblings sits toward the parent's interior on {@code axis}:
     * a low child's interior neighbor is at {@code +size}, a high child's at {@code -size}. */
    private int interiorStep(int axis) {
        int axisStart = axis == 0 ? start.getX() : axis == 1 ? start.getY() : start.getZ();
        boolean low = Math.floorMod(axisStart, size * 2) == 0;
        return low ? 1 : -1;
    }

    private static Vec3i axisUnit(int axis, int sign) {
        return new Vec3i(axis == 0 ? sign : 0, axis == 1 ? sign : 0, axis == 2 ? sign : 0);
    }

    /**
     * Resolves a single edge-pinwheel neighbor in {@code direction} (possibly diagonal across two
     * axes): same-parent case is a free lookup in {@code parent.leaves}; cross-parent falls
     * through to {@link #neighbor}. {@link #neighbor} can throw if the target chunk/section isn't
     * reachable through {@code chunk} (e.g. not loaded) -- that's a real possible runtime state
     * (unlike the same-size accessor's own tests, which always hand it a fully-wired chain), so
     * this composing method degrades to an empty placeholder positioned where the real neighbor
     * would be rather than propagating the failure.
     */
    private @NotNull Branch resolveEdgeNeighbor(@NotNull Vec3i direction, @Nullable Branch parent, @Nullable ChunkChain chunk) {
        if (parent != null && !crossesParentBoundary(direction)) {
            BlockPos childOrigin = start.add(direction.getX() * size, direction.getY() * size, direction.getZ() * size);
            Branch sibling = parent.leaves.get(childOrigin.asLong());
            if (sibling != null) return sibling;
        }
        try {
            return neighbor(direction, chunk);
        } catch (NullPointerException unresolved) {
            BlockPos shiftedStart = start.add(direction.getX() * size, direction.getY() * size, direction.getZ() * size);
            return new Branch(shiftedStart, size);
        }
    }
}
