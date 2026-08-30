package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.debug.math.OctantColor;
import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.MaterialRegistry;
import dev.thedocruby.resounding.Physics;
import dev.thedocruby.resounding.toolbox.ChunkChain;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
    /** Impedance the ray was in immediately before crossing into the {@link #impeded} medium — lets the
     *  next boundary detect "exited straight back into the same medium" (a thin partition) instead of
     *  paying a second full impedance-mismatch reflection on the way out. See research/transmission-upgrade.md. */
    private @Nullable Double enteredFrom = null;
    /** Distance traveled through the {@link #impeded} medium before this boundary — the "thickness" the
     *  next call checks against so a thin-partition exit isn't confused with a real second surface reached
     *  after traveling a long way through a thick, similarly-impedanced medium (e.g. two separate stone walls). */
    private @Nullable Double enteredThickness = null;
    /** Relative tolerance for treating two impedances as "the same medium" when detecting thin-partition exits. */
    private static final double THIN_MEMBRANE_RELATIVE_TOLERANCE = 0.25;
    /** Max thickness (blocks) a medium can be for exiting it to count as a thin-partition pass-through. */
    private static final double THIN_MEMBRANE_MAX_THICKNESS = 1.5;
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

//    public static Material air = Cache.material(Blocks.AIR.getDefaultState());

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
        // assert vector != null; // the power check above will catch this
        final Vec3d normalized = normalize(position,vector);
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
        // priorImpedance is the medium the ray was previously traveling through; recorded on every
        // path (even pass-throughs) so telemetry can show what a reflection was actually computed against.
        final double priorImpedance = impeded == null
                ? material(Blocks.AIR.getDefaultState()).impedance()
                : impeded;
        this.lastPriorImpedance = priorImpedance;
        // } */
        // prepare variables
        Step step, rstep;
        Vec3d  pposition, rposition;
        double pdistance, rdistance;

        VoxelShape shape = branch.shape;
        if (shape == null) {
            shape = ((WorldChunk) this.chunk).getBlockState(branch.start).getCollisionShape(world, branch.start);
        }

        ShapeTraversal.Result geometry = ShapeTraversal.resolve(
                branch.start, branch.size, position, vector, shape, this::getStep
        );

        if (geometry.mode() == ShapeTraversal.Mode.AIR_CELL) {
            transmit(power, geometry.transmitPosition(), vector, geometry.permeationDistance());
            reflect(0, position, null, 0);
            stood = geometry.step();
            this.lastReflectivity = 0.0;
            this.lastTransmission = 1.0;
            this.lastMaterial = null;
            return;
        }

        if (geometry.mode() == ShapeTraversal.Mode.SHAPE) {
            step = geometry.step();
            rstep = geometry.reflectStep();
            pdistance = geometry.permeationDistance();
            pposition = geometry.transmitPosition();
            rdistance = geometry.reflectDistance();
            rposition = geometry.reflectPosition();
        } else {
            //* true voxel handling {
            step = getStep(blockToVec(branch.start), branch.size, position, vector);
            pdistance = step.step().length();
            pposition = ShapeTraversal.truncate(position.add(step.step()));
            // } */
            rstep = step;
            rdistance = 0;
            rposition = position;
            if (branch.size == 1) {
                Step next = bounce(branch, position, vector);
                if (next != null) {
                    rstep = next;
                    rdistance = rstep.step().subtract(position).length();
                    rposition = rstep.step();
                }
            }
        }
        //* amplitude and vector {
        // material properties — skip attenuation when passing through open cell space
        if (branch.material == null) {
            transmit(power, pposition, vector, pdistance);
            reflect(0, rposition, null, rdistance);
            stood = step;
            this.lastReflectivity = 0.0;
            this.lastTransmission = 1.0;
            this.lastMaterial = null;
            return;
        }

        final double newImpedance = branch.material.impedance();
        // Exiting straight back into (about) the medium we entered the last branch from means this
        // was a thin partition, not a fresh semi-infinite boundary — skip the second full-mismatch
        // reflection instead of nearly soundproofing every 1-block wall/pane. See research/transmission-upgrade.md.
        final boolean exitingThinMembrane = isThinMembraneExit(enteredFrom, enteredThickness, newImpedance);

        double reflectivity = exitingThinMembrane ? 0.0 : Physics.reflection(priorImpedance, newImpedance);
        double transmission = exitingThinMembrane
                ? Math.pow(branch.material.permeation(), pdistance)
                : (1 - reflectivity) * Math.pow(branch.material.permeation(), pdistance);

        // if reflection / permeation -> calculate -> bounce / refract
        @Nullable Vec3d reflected = reflectivity > 0 ? Physics.pseudoReflect(vector,rstep.plane()) : null;
        // use single-surface refraction here, unpredictable effects with larger objects & permeation coefficients
        // TODO: remove fresnel in favor of atmospheric effects
        // TODO: branch here to avoid calculations on last raycast
        @Nullable Vec3d transmitted = transmission > 0
                ? Physics.pseudoReflect(vector, step.plane(), transmission / 5)
                : null;
        if (transmitted == null) {
            transmitted = vector;
        }
        // } */
        // apply movement
        reflect(reflectedPower(reflectivity, power), rposition, reflected, rdistance);
        transmit(transmission*power, pposition, transmitted, pdistance);
        stood = step; // TODO ?
        this.lastReflectivity = reflectivity;
        this.lastTransmission = transmission;
        this.lastMaterial = branch.material;
        this.enteredFrom = priorImpedance;
        this.enteredThickness = pdistance;
        this.impeded = newImpedance;
    }

    static boolean isThinMembraneExit(@Nullable Double enteredFrom, @Nullable Double enteredThickness, double newImpedance) {
        return enteredFrom != null
                && enteredThickness != null && enteredThickness <= THIN_MEMBRANE_MAX_THICKNESS
                && withinRelativeTolerance(newImpedance, enteredFrom);
    }

    static boolean withinRelativeTolerance(double a, double b) {
        double scale = Math.max(Math.abs(a), Math.abs(b));
        return scale > 0 && Math.abs(a - b) <= THIN_MEMBRANE_RELATIVE_TOLERANCE * scale;
    }
    // } */

    //* fetch {
    public static Vec3d normalize(@NotNull Vec3d pos, @NotNull Vec3d vector) {
        //return pos;
        return new Vec3d(
                vector.x < 0 ? Math.ceil(pos.x) - 1 : Math.floor(pos.x),
                vector.y < 0 ? Math.ceil(pos.y) - 1 : Math.floor(pos.y),
                vector.z < 0 ? Math.ceil(pos.z) - 1 : Math.floor(pos.z));
        // */
    }
    public Branch getBlock(Vec3d pos) {
        if (this.chunk == null || this.tree == null) return null;
        final BlockPos block = BlockPos.ofFloored(pos);
        BlockState state = ((WorldChunk) this.chunk).getBlockState(block);
        VoxelShape shape = state.getCollisionShape(world, block);
        Material mat = material(state);

        final Branch branch = this.tree.get(block);
        // Fall through to live block data only at 1³ leaves; null material on a large node
        // means heterogeneous — tree.get() should have descended, or siblings still use coarse cells.
        if (branch.material == null && branch.size == 1) {
            return liveLeaf(block, shape, mat, state);
        }
        if (branch.size > 1) {
            return branch;
        }
        // 1³ cached leaf — always use live collision geometry for ray/shape tests.
        return liveLeaf(block, shape, mat, state);
    }

    /** 1³ leaves are always re-fetched live (see above); stamp the debug label too, or telemetry shows "?" for nearly every bounce. */
    private static Branch liveLeaf(BlockPos block, VoxelShape shape, Material mat, BlockState state) {
        Branch leaf = new Branch(block, 1, shape, mat);
        leaf.materialLabel = MaterialRegistry.describe(state);
        return leaf;
    }
    public static Vec3d blockToVec(BlockPos pos) { return new Vec3d(pos.getX(), pos.getY(), pos.getZ()); }
    // } */
    //* getBoundStep injections {
    // could be static if other constraints weren't here.
    private Pair<Double, Vec3i> noTarget(Vec3d position, Vec3d vector) { return new Pair<>(Double.POSITIVE_INFINITY, Vec3i.ZERO); }
    private Pair<Double, Vec3i> target(Vec3d position, Vec3d vector) {
        // TODO consider (== soundChunk) check
        // NOTE unnecessary - final & handled in constructor. This fn isn't used when this value is null
        // assert this.targetPos != null;
        return getStepPair(this.targetPos, 0, position, vector);
    }
    // } */
    //* physics {
    // runtime dependency injection pattern prevents this from being static
    private Step getStep(Vec3d base, int size, Vec3d position, Vec3d vector) {
        /* return a new position, based on which bounding wall will be hit first
         * this is for path tracing using an octree
         */
        final Pair<Double, Vec3i> pair = getStepPair(base, size, position, vector);
        final Pair<Double, Vec3i> target = stepFinder.apply(position, vector);
        double coefficient = pair.getLeft();
        Vec3i  planarIndex = pair.getRight();
        // this does not belong inside getStepPair
        if (target.getLeft() < coefficient) {
            coefficient = target.getLeft();
            planarIndex = target.getRight();
        }
        return new Step(vector.multiply(coefficient), planarIndex);
    }
    private static Pair<Double,Vec3i> getStepPair(Vec3d base, int size, Vec3d position, Vec3d vector) {
        /*
         ** base     = diquad start position
         ** size     = diquad size
         ** position = ray position
         ** vector   = ray trajectory
         */

        // TODO profile intercalating comparisons vs separated approach
        // normalize magnitude to closest wall
        double coefficient = boundAxis(base.x, position.x, size, vector.x);
        double ystep       = boundAxis(base.y, position.y, size, vector.y);
        double zstep       = boundAxis(base.z, position.z, size, vector.z);

        Vec3i planarIndex  = new Vec3i(MathHelper.floor(-Math.signum(vector.x)), 0, 0);

        // branch hint: 1/3 probability -> NO
        // same as min(x,min(y,z)) + planar index
        if (ystep < coefficient) {
            coefficient = ystep;
            planarIndex = new Vec3i(0,MathHelper.floor(-Math.signum(vector.y)),0);
        }
        if (zstep < coefficient) {
            coefficient = zstep;
            planarIndex = new Vec3i(0,0,MathHelper.floor(-Math.signum(vector.z)));
        }
        if (coefficient == Double.POSITIVE_INFINITY) {
            LOGGER.warn("invalid coefficient");
            // coefficient = epsilon;
        }
        // closest wall -> magnitude
        return new Pair(coefficient,planarIndex);
    }
    private static double boundAxis(double base, double pos, double size, double dir) {
        // normalize position, determine distance, apply direction & normalize coefficient
        double value = (base - pos  +  (dir > 0 ? size : 0)) / dir;
        // theoretically zeroes/negatives shouldn't ever happen, but they did extensively during debugging
        // (and were promptly fixed!) But you can't ever be too sure.
        if (value <= 0 || Double.isNaN(value)) value = Double.POSITIVE_INFINITY; // endless loops -> always bigger
        return value;
        /*     (dist + (   size   )) / vector = magnitude
         *     (   1 + (16  * 0   )) / -2     = -1/2
         *     (   7 + (16  * 1   )) /  2     = 14/2
         */
    }

    private @Nullable Step bounce(Branch branch, Vec3d start, Vec3d vector) {
        final long posl = branch.start.asLong();
        Map<Long, VoxelShape> shapes = chunk.getShapes();
        VoxelShape shape = shapes.get(posl);
        // TODO evaluate actual benefit for shape cache
        if (shape == null) {
            // if (pConfig.dRays) world.addParticle(ParticleTypes.END_ROD, false, branch.start.getX() + 0.5d, branch.start.getY() + 1d, branch.start.getZ() + 0.5d, vector.x, vector.y, vector.z);
            shape = branch.shape;
            if (shape == null) return null;
            shapes.put(posl, shape);
        }
        if (shape == CUBE || shape == EMPTY) return null;

        BlockHitResult hit = shape.raycast(start, start.add(vector.multiply(2)), branch.start);
        return hit == null ? null : new Step(hit.getPos(),hit.getSide().getVector());
    }
    // } */
    //* mutate {
    public void blank(Vec3d position) {
        this.reflected = new Ray(0, position, null, 0);
        this.transmitted = new Ray(0, position, null, 0);
    }
    private void reflect(/*MaterialData material,*/ double power, Vec3d position, Vec3d angle, double distance) {
        this.reflected = new Ray(power, position, angle, distance);
    }
    /**
     * Reflected power follows the impedance-derived power reflection coefficient directly.
     * {@code reflectivity} already is the fraction of incident power reflected at the boundary
     * (see {@link dev.thedocruby.resounding.material.Acoustics#reflection}); softer materials
     * already lose more energy here because they have lower reflectivity. Applying an extra
     * sqrt(reflectivity)-derived absorption on top double-counts that loss and made rays die out
     * after only a few bounces off anything but a near-perfect reflector.
     */
    public static double reflectedPower(double reflectivity, double incidentPower) {
        return reflectivity * incidentPower;
    }
    private void transmit(/*MaterialData material,*/ double power, Vec3d position, Vec3d angle, double distance) {
        this.transmitted = new Ray(power, position, angle, distance);
    }
    // } */
}
