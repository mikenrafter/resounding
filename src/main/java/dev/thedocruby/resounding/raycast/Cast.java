package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.debug.math.OctantColor;
import dev.thedocruby.resounding.material.Acoustics;
import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.MaterialRegistry;
import dev.thedocruby.resounding.Physics;
import dev.thedocruby.resounding.toolbox.ChunkChain;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.util.Pair;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.function.BiFunction;

import static dev.thedocruby.resounding.MaterialRegistry.material;
import static dev.thedocruby.resounding.OctreeManager.CUBE;
import static dev.thedocruby.resounding.OctreeManager.EMPTY;
import static dev.thedocruby.resounding.Utils.LOGGER;

@Environment(EnvType.CLIENT)
public class Cast {

    private @Nullable final Vec3d targetPos;
    private final BiFunction<Vec3d, Vec3d, Pair<Double, Vec3i>> stepFinder;
    public @NotNull World world;

    public @Nullable ChunkChain chunk = null;
    public @Nullable Branch tree = null;

    public @Nullable Ray reflected = null;
    public @Nullable Ray transmitted = null;

    public @Nullable Step stood = null; // prior position
    public @Nullable Double impeded = null; // prior impedance
    /** Impedance of the medium the ray was in before entering the current solid transit. */
    @Nullable Double enteredFrom = null;
    /** Distance permeated through the current solid transit (blocks). */
    double solidTransitDistance = 0.0;
    double lastPermeationDistance = 0.0;

    static final double THIN_MEMBRANE_MAX_THICKNESS = 1.5;
    static final double IMPEDANCE_MATCH_TOLERANCE = 0.15;
    static final double SOLID_IMPEDANCE_MIN = 10_000.0;

    public @Nullable Double lastReflectivity;
    public @Nullable Double lastTransmission;
    public @Nullable Material lastMaterial;
    public int lastOctantColor;
    /** Impedance this bounce's reflectivity was computed against — the medium the ray was previously in. */
    public double lastPriorImpedance;
    /** Octree node size (in blocks) the boundary was resolved at; >1 means a coarse cached node, not a single voxel. */
    public int lastBranchSize;
    /** Human-readable material tag for the branch entered, for telemetry/debug readouts. */
    public @Nullable String lastMaterialLabel;
    /** Whether this bounce resolved via sub-voxel VoxelShape geometry rather than full-cube stepping. */
    public boolean lastShapeMode;

    public Cast(@NotNull World world, @Nullable Branch tree, @Nullable ChunkChain chunk, @Nullable Vec3d targetPos) {
        this.world = world;
        this.tree = tree;
        this.chunk = chunk;
        this.targetPos = targetPos;
        this.stepFinder = targetPos == null ? this::noTarget : this::target;
    }
    public Cast(@NotNull World world, @Nullable Branch tree, @Nullable ChunkChain chunk) {
        this(world, tree, chunk, null);
    }
    //* raycast {
    public void raycast(@NotNull Vec3d position, @NotNull Vec3d angle) {
        // TODO: settings.rayStrength & volume -> amplitude
        raycast(position, angle, 128);
    }
    public void raycast(@NotNull Vec3d position, @NotNull Vec3d vector, double power) {
        //* access branch {
        final Vec3d normalized = normalize(position, vector);
        chunk = chunk.access((int) normalized.x >> 4, (int) normalized.z >> 4);
        if (chunk != null) tree = chunk.getBranch((int) normalized.y >> 4);
        Branch branch = getBlock(normalized);
        if (branch == null) {
            blank(position);
            return;
        }
        this.lastOctantColor = OctantColor.forNode(branch.start, branch.size);
        this.lastBranchSize = branch.size;
        this.lastMaterialLabel = branch.materialLabel;
        this.lastPriorImpedance = impeded == null ? 0.0 : impeded;
        // } */
        // prepare variables
        Step step, rstep;
        Vec3d pposition, rposition;
        double pdistance, rdistance;
        // Full-cube reflections resolve rposition to the exact integer boundary the ray is
        // already standing on; sub-voxel (SHAPE / bounce) reflections resolve it to real
        // floating-point surface geometry that may need an off-surface nudge. Only the latter
        // should be nudged — nudging a grid-exact boundary knocks it off the whole number that
        // Cast.normalize()'s sign-of-vector octant pick depends on for the next cast.
        boolean gridAlignedReflect = false;
        @Nullable Step shapeEntryStep = null;

        VoxelShape shape = branch.shape;
        if (shape == null) {
            shape = ((WorldChunk) this.chunk).getBlockState(branch.start).getCollisionShape(world, branch.start);
        }

        ShapeTraversal.Result geometry = ShapeTraversal.resolve(
                branch.start, branch.size, position, vector, shape, this::getStep
        );

        if (geometry.mode() == ShapeTraversal.Mode.SHAPE) {
            step = geometry.step();
            rstep = geometry.reflectStep();
            shapeEntryStep = geometry.entryStep();
            pdistance = geometry.permeationDistance();
            pposition = geometry.transmitPosition();
            rdistance = geometry.reflectDistance();
            rposition = geometry.reflectPosition();
        } else {
            step = getStep(blockToVec(branch.start), branch.size, position, vector);
            pdistance = step.step().length();
            pposition = exitPosition(position, step, blockToVec(branch.start), branch.size);
            rstep = step;
            rdistance = 0;
            rposition = position;
            gridAlignedReflect = true;
            if (branch.size == 1
                    && ShapeTraversal.isPartialSolid(shape)
                    && ShapeTraversal.containsLocalPoint(shape, branch.start, position)) {
                Step next = bounce(branch, position, vector);
                if (next != null) {
                    rstep = next;
                    rdistance = rstep.step().subtract(position).length();
                    rposition = rstep.step();
                    gridAlignedReflect = false;
                }
            }
        }
        //* amplitude and vector {
        Material branchMaterial = branch.material;
        if (branchMaterial == null) {
            BlockState state = ((WorldChunk) this.chunk).getBlockState(BlockPos.ofFloored(normalized));
            branchMaterial = material(state);
        }
        Material interactionMaterial = interactionMaterial(
                branchMaterial, shape, branch.start, position, geometry.mode()
        );

        // Skip reflectivity on the first cast so a sound originating inside a block does not
        // immediately reflect back into that same block.
        double newImpedance = interactionMaterial.impedance();
        boolean thinExit = impeded != null && isThinMembraneExit(newImpedance);
        double reflectivity;
        if (impeded == null) {
            reflectivity = 0;
        } else if (thinExit) {
            reflectivity = 0;
        } else {
            reflectivity = Physics.reflection(impeded, newImpedance);
        }
        double permeationFactor = Acoustics.permeationOverDistance(
                interactionMaterial.permeation(), interactionMaterial.granularity(), pdistance);
        double transmission = thinExit
                ? permeationFactor
                : (1 - reflectivity) * permeationFactor;

        boolean shapeMode = geometry.mode() == ShapeTraversal.Mode.SHAPE;
        this.lastShapeMode = shapeMode;
        Vec3i transmitPlane = shapeMode ? rstep.plane() : step.plane();
        Vec3i reflectPlane;
        if (gridAlignedReflect) {
            reflectPlane = entryPlane(position, blockToVec(branch.start), branch.size, vector);
        } else if (shapeMode && shapeEntryStep != null) {
            reflectPlane = shapeEntryStep.plane();
        } else {
            reflectPlane = rstep.plane();
        }
        @Nullable Vec3d reflected = reflectivity > 0 ? Physics.pseudoReflect(vector, reflectPlane) : null;
        @Nullable Vec3d transmitted = Physics.pseudoReflect(vector, transmitPlane, transmission / 5);
        Vec3d reflectStart = gridAlignedReflect ? rposition : nudgeReflectOrigin(rposition, reflected, rdistance);
        // } */
        // apply movement
        reflect(reflectivity * power, reflectStart, reflected, rdistance);
        transmit(transmission * power, pposition, transmitted, pdistance);
        stood = step;
        this.lastPermeationDistance = pdistance;
        this.lastReflectivity = reflectivity;
        this.lastTransmission = transmission;
        this.lastMaterial = interactionMaterial;
    }

    /**
     * Partial solids (doors, panes, etc.) can contain open air inside the 1³ cell. When the ray
     * is in that air — whether on the first cast or while permeating through the cell — use air
     * impedance so it can voxel-step out instead of reflecting off interior geometry. Inside solid
     * sub-voxel geometry (SHAPE mode or a point inside the collision boxes) keeps the block
     * material.
     */
    static Material interactionMaterial(
            Material branchMaterial,
            VoxelShape shape,
            BlockPos origin,
            Vec3d position,
            ShapeTraversal.Mode mode
    ) {
        if (!ShapeTraversal.isPartialSolid(shape)) {
            return branchMaterial;
        }
        if (mode == ShapeTraversal.Mode.SHAPE) {
            return branchMaterial;
        }
        if (!ShapeTraversal.containsLocalPoint(shape, origin, position)) {
            return MaterialRegistry.DEFAULT;
        }
        return branchMaterial;
    }

    private static final double REFLECT_NUDGE = 1e-4;

    static Vec3d nudgeReflectOrigin(Vec3d origin, @Nullable Vec3d reflected, double reflectDistance) {
        if (reflectDistance > REFLECT_NUDGE || reflected == null) {
            return origin;
        }
        double length = reflected.length();
        if (length <= REFLECT_NUDGE) {
            return origin;
        }
        return origin.add(reflected.multiply(REFLECT_NUDGE / length));
    }

    static boolean impedancesClose(double a, double b) {
        double max = Math.max(Math.abs(a), Math.abs(b));
        if (max < 1e-6) {
            return true;
        }
        return Math.abs(a - b) / max < IMPEDANCE_MATCH_TOLERANCE;
    }

    static boolean isSolidImpedance(double impedance) {
        return impedance >= SOLID_IMPEDANCE_MIN;
    }

    /**
     * Exiting a thin solid back into the same medium the ray entered from — skip the second
     * full half-space mismatch (see {@code research/transmission-upgrade.md}).
     */
    boolean isThinMembraneExit(double newImpedance) {
        if (enteredFrom == null || solidTransitDistance > THIN_MEMBRANE_MAX_THICKNESS) {
            return false;
        }
        if (impeded == null || !isSolidImpedance(impeded)) {
            return false;
        }
        return impedancesClose(newImpedance, enteredFrom);
    }

    /** Commits the entered branch impedance after the caller chooses permeation (not reflection). */
    public void commitPermeation() {
        if (lastMaterial == null) {
            return;
        }
        double newImpedance = lastMaterial.impedance();
        double step = lastPermeationDistance;

        if (impeded != null && enteredFrom == null
                && isSolidImpedance(newImpedance) && !isSolidImpedance(impeded)) {
            enteredFrom = impeded;
            solidTransitDistance = 0.0;
        }

        if (isSolidImpedance(newImpedance)) {
            solidTransitDistance += step;
        }

        impeded = newImpedance;

        if (enteredFrom != null && impedancesClose(newImpedance, enteredFrom)) {
            enteredFrom = null;
            solidTransitDistance = 0.0;
        }
    }
    // } */

    //* fetch {
    public static Vec3d normalize(@NotNull Vec3d pos, @NotNull Vec3d vector) {
        return new Vec3d(
                vector.x < 0 ? Math.ceil(pos.x) - 1 : Math.floor(pos.x),
                vector.y < 0 ? Math.ceil(pos.y) - 1 : Math.floor(pos.y),
                vector.z < 0 ? Math.ceil(pos.z) - 1 : Math.floor(pos.z));
    }

    static Vec3d truncate(Vec3d position) {
        return ShapeTraversal.truncate(position);
    }

    /**
     * Position where a ray leaves the current octree branch. The axis {@code step} crossed on is
     * set to the branch's exact edge coordinate (not the float arithmetic's result, which can be a
     * hair off it) so the ray lands precisely on the whole number {@link #normalize} needs to pick
     * the correct adjacent octant by sign. The other two axes get 5-decimal rounding, same as
     * before — they're genuinely continuous, not edge-exact.
     * <p>Only valid when {@code step}'s plane came from the branch boundary rather than this cast's
     * target position (see {@link #stepFinder}); skipped whenever a target is in play.
     */
    private Vec3d exitPosition(Vec3d position, Step step, Vec3d base, int size) {
        Vec3d raw = position.add(step.step());
        if (targetPos != null) {
            return ShapeTraversal.truncate(raw);
        }
        Vec3i plane = step.plane();
        return new Vec3d(
                plane.getX() != 0 ? (plane.getX() < 0 ? base.x + size : base.x) : round5(raw.x),
                plane.getY() != 0 ? (plane.getY() < 0 ? base.y + size : base.y) : round5(raw.y),
                plane.getZ() != 0 ? (plane.getZ() < 0 ? base.z + size : base.z) : round5(raw.z)
        );
    }

    private static double round5(double value) {
        return Math.round(value * 1e5) / 1e5;
    }

    /**
     * Normal of the branch face {@code position} is currently standing on — the boundary the ray
     * just crossed to get here — found by matching {@code position} against the branch's own edges
     * rather than probing forward like {@link #getStep} does for the exit face. Trig-free: axis
     * normals are always exactly one of the six unit directions, so this is a sign check per axis.
     * Only meaningful once {@code position} is guaranteed edge-exact (see {@link #exitPosition}); on
     * the very first cast from a sound source it won't match anything, but reflectivity is forced to
     * 0 there anyway so the caller never uses the result.
     */
    private static Vec3i entryPlane(Vec3d position, Vec3d base, int size, Vec3d vector) {
        if (position.x == base.x || position.x == base.x + size) {
            return new Vec3i(MathHelper.floor(-Math.signum(vector.x)), 0, 0);
        }
        if (position.y == base.y || position.y == base.y + size) {
            return new Vec3i(0, MathHelper.floor(-Math.signum(vector.y)), 0);
        }
        if (position.z == base.z || position.z == base.z + size) {
            return new Vec3i(0, 0, MathHelper.floor(-Math.signum(vector.z)));
        }
        return Vec3i.ZERO;
    }

    public Branch getBlock(Vec3d pos) {
        if (this.chunk == null || this.tree == null) return null;
        final BlockPos block = BlockPos.ofFloored(pos);
        BlockState state = ((WorldChunk) this.chunk).getBlockState(block);
        VoxelShape shape = state.getCollisionShape(world, block);
        Material mat = material(state);

        final Branch branch = this.tree.get(block);
        if (branch.material == null && branch.size == 1) {
            return liveLeaf(block, shape, mat, state);
        }
        if (branch.size > 1) {
            return branch;
        }
        return liveLeaf(block, shape, mat, state);
    }

    private static Branch liveLeaf(BlockPos block, VoxelShape shape, Material mat, BlockState state) {
        Branch leaf = new Branch(block, 1, shape, mat);
        leaf.materialLabel = MaterialRegistry.describe(state);
        return leaf;
    }
    public static Vec3d blockToVec(BlockPos pos) { return new Vec3d(pos.getX(), pos.getY(), pos.getZ()); }
    // } */
    //* getBoundStep injections {
    private Pair<Double, Vec3i> noTarget(Vec3d position, Vec3d vector) { return new Pair<>(Double.POSITIVE_INFINITY, Vec3i.ZERO); }
    private Pair<Double, Vec3i> target(Vec3d position, Vec3d vector) {
        return getStepPair(this.targetPos, 0, position, vector);
    }
    // } */
    //* physics {
    private Step getStep(Vec3d base, int size, Vec3d position, Vec3d vector) {
        final Pair<Double, Vec3i> pair = getStepPair(base, size, position, vector);
        final Pair<Double, Vec3i> target = stepFinder.apply(position, vector);
        double coefficient = pair.getLeft();
        Vec3i planarIndex = pair.getRight();
        if (target.getLeft() < coefficient) {
            coefficient = target.getLeft();
            planarIndex = target.getRight();
        }
        return new Step(vector.multiply(coefficient), planarIndex);
    }
    private static Pair<Double,Vec3i> getStepPair(Vec3d base, int size, Vec3d position, Vec3d vector) {
        double coefficient = boundAxis(base.x, position.x, size, vector.x);
        double ystep       = boundAxis(base.y, position.y, size, vector.y);
        double zstep       = boundAxis(base.z, position.z, size, vector.z);

        Vec3i planarIndex  = new Vec3i(MathHelper.floor(-Math.signum(vector.x)), 0, 0);

        if (ystep < coefficient) {
            coefficient = ystep;
            planarIndex = new Vec3i(0, MathHelper.floor(-Math.signum(vector.y)), 0);
        }
        if (zstep < coefficient) {
            coefficient = zstep;
            planarIndex = new Vec3i(0, 0, MathHelper.floor(-Math.signum(vector.z)));
        }
        if (coefficient == Double.POSITIVE_INFINITY) {
            LOGGER.warn("invalid coefficient");
        }
        return new Pair<>(coefficient, planarIndex);
    }
    private static double boundAxis(double base, double pos, double size, double dir) {
        double value = (base - pos + (dir > 0 ? size : 0)) / dir;
        if (value <= 0 || Double.isNaN(value)) value = Double.POSITIVE_INFINITY;
        return value;
    }

    private @Nullable Step bounce(Branch branch, Vec3d start, Vec3d vector) {
        final long posl = branch.start.asLong();
        Map<Long, VoxelShape> shapes = chunk.getShapes();
        VoxelShape shape = shapes.get(posl);
        if (shape == null) {
            shape = branch.shape;
            if (shape == null) return null;
            shapes.put(posl, shape);
        }
        if (shape == CUBE || shape == EMPTY) return null;

        BlockHitResult hit = shape.raycast(start, start.add(vector.multiply(2)), branch.start);
        if (hit == null) return null;
        Vec3i side = hit.getSide().getVector();
        return new Step(ShapeTraversal.snapHitPosition(hit.getPos(), side, branch.start), side);
    }
    // } */
    //* mutate {
    public void blank(Vec3d position) {
        this.reflected = new Ray(0, position, null, 0);
        this.transmitted = new Ray(0, position, null, 0);
    }
    private void reflect(double power, Vec3d position, Vec3d angle, double distance) {
        this.reflected = new Ray(power, position, angle, distance);
    }
    private void transmit(double power, Vec3d position, Vec3d angle, double distance) {
        this.transmitted = new Ray(power, position, angle, distance);
    }
    // } */
}
