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
import static dev.thedocruby.resounding.config.PrecomputedConfig.pConfig;

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
    public double impeded; // prior impedance
    /** False while the ray is still exiting the emission cell (equivalent to the old {@code impeded == null}). */
    public boolean impededSet = false;
    /** Impedance of the medium the ray was in before entering the current solid transit. */
    @Nullable Double enteredFrom = null;
    /** Distance permeated through the current solid transit (blocks). */
    double solidTransitDistance = 0.0;
    double lastPermeationDistance = 0.0;

    static final double THIN_MEMBRANE_MAX_THICKNESS = 1.5;
    static final double IMPEDANCE_MATCH_TOLERANCE = 0.15;
    static final double SOLID_IMPEDANCE_MIN = 10_000.0;

    public double lastReflectivity;
    public double lastTransmission;
    /** False until {@link #raycast} has resolved a boundary at least once (equivalent to the old
     *  {@code lastReflectivity == null}/{@code lastTransmission == null}) — both fields are always
     *  assigned together at the single site in {@link #raycast}, so they share one flag. */
    public boolean lastBoundaryResolved = false;
    /** Polar alignment {@code (rayNorm·polNorm)²} at the last resolved boundary; 1.0 (unthrottled) when the boundary had no polarization vector. */
    public double lastPolarAlignment = 1.0;
    public @Nullable Material lastMaterial;
    public int lastOctantColor;
    /** Impedance this bounce's reflectivity was computed against — the medium the ray was previously in. */
    public double lastPriorImpedance;
    /** Octree node size (in blocks) the boundary was resolved at; >1 means a coarse cached node, not a single voxel. */
    public int lastBranchSize;
    /** Origin of the host cell ({@code H}) the boundary was resolved at — together with
     *  {@link #lastBranchSize} identifies the quartet for the stall guard in {@code Engine}. */
    public @Nullable BlockPos lastCellOrigin;
    /** Human-readable material tag for the branch entered, for telemetry/debug readouts. */
    public @Nullable String lastMaterialLabel;
    /** Whether this bounce resolved via sub-voxel VoxelShape geometry rather than full-cube stepping. */
    public boolean lastShapeMode;

    /**
     * Running frustum footprint width (blocks). Advances only via {@link #applyFrustumStep}
     * (which calls {@link FrustumLod#nextFrustumSize}) — never reassigned wholesale like a distance
     * would be.
     */
    public double frustumSize = FrustumLod.BASE_FOOTPRINT;
    /** Remaining beam split budget for notable-interaction / commit decisions. */
    public @NotNull BeamBudget beamBudget = BeamBudget.full();

    /** Block cell the sound was born in; emission always exits this 1³ cell as a cube. */
    public @Nullable BlockPos originBlock;

    /** Face-neighbor impedances sampled before launch, indexed by {@link #FACE_OFFSETS}. */
    @Nullable double[] emissionNeighborImpedances;
    double emissionNeighborAverageImpedance = Double.NaN;
    double emissionSourceImpedance = Double.NaN;

    static final Vec3i[] FACE_OFFSETS = {
            new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0),
            new Vec3i(0, 1, 0), new Vec3i(0, -1, 0),
            new Vec3i(0, 0, 1), new Vec3i(0, 0, -1),
    };

    // Flyweight axis-unit vectors: getStepPair/entryPlane resolve one of only 7 possible planar
    // indices (±1 on exactly one axis, or ZERO) per call, in the per-ray-step hot path. Caching
    // these avoids a `new Vec3i` allocation every step instead of requiring a mutable vector type
    // (MC's Vec3i/Vec3d are immutable).
    private static final Vec3i UNIT_POS_X = new Vec3i(1, 0, 0);
    private static final Vec3i UNIT_NEG_X = new Vec3i(-1, 0, 0);
    private static final Vec3i UNIT_POS_Y = new Vec3i(0, 1, 0);
    private static final Vec3i UNIT_NEG_Y = new Vec3i(0, -1, 0);
    private static final Vec3i UNIT_POS_Z = new Vec3i(0, 0, 1);
    private static final Vec3i UNIT_NEG_Z = new Vec3i(0, 0, -1);

    /** {@code MathHelper.floor(-Math.signum(dir))} as a cached axis-unit vector: {@code dir > 0} steps
     *  negative on this axis, {@code dir < 0} steps positive, {@code dir == 0} doesn't cross it. */
    private static Vec3i xUnit(double dir) { return dir > 0 ? UNIT_NEG_X : dir < 0 ? UNIT_POS_X : Vec3i.ZERO; }
    private static Vec3i yUnit(double dir) { return dir > 0 ? UNIT_NEG_Y : dir < 0 ? UNIT_POS_Y : Vec3i.ZERO; }
    private static Vec3i zUnit(double dir) { return dir > 0 ? UNIT_NEG_Z : dir < 0 ? UNIT_POS_Z : Vec3i.ZERO; }

    static final double EMISSION_EXIT_NUDGE = 1e-4;

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

    /** Falls back to the live block's collision shape when the branch has no baked shape. */
    private VoxelShape resolveShape(Branch branch) {
        VoxelShape shape = branch.shape;
        if (shape == null) {
            shape = ((WorldChunk) this.chunk).getBlockState(branch.start).getCollisionShape(world, branch.start);
        }
        return shape;
    }

    /** Falls back to the live block state's material when the branch has no baked material. */
    private Material resolveMaterial(Branch branch, BlockPos pos) {
        Material branchMaterial = branch.material;
        if (branchMaterial == null) {
            BlockState state = ((WorldChunk) this.chunk).getBlockState(pos);
            branchMaterial = material(state);
        }
        return branchMaterial;
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
        if (tree == null || chunk == null) {
            blank(position);
            return;
        }

        final boolean emissionCast = !impededSet;
        int requestedLod = emissionCast ? 1 : FrustumLod.stepForSize(frustumSize);
        BlockPos queryPos = BlockPos.ofFloored(normalized);
        Branch lodBranch = tree.getAtLod(queryPos, requestedLod);
        Branch finest = tree.get(queryPos);
        // Prefer live leaf fill for size-1 unresolved materials (existing getBlock behavior).
        Branch branch = lodBranch;
        if (lodBranch.size == 1) {
            Branch live = getBlock(normalized);
            if (live != null) {
                branch = live;
            }
        }

        if (branch == null) {
            blank(position);
            return;
        }

        int cellSize = emissionCast ? 1 : Math.min(requestedLod, Math.max(1, branch.size));
        BlockPos cellOrigin = emissionCast && originBlock != null
                ? originBlock
                : FrustumLod.alignOrigin(queryPos, branch.start, cellSize);
        Vec3d cellBase = blockToVec(cellOrigin);
        boolean virtualLod = !emissionCast && finest.size > cellSize;

        this.lastOctantColor = OctantColor.forNode(cellOrigin, cellSize);
        this.lastBranchSize = cellSize;
        this.lastCellOrigin = cellOrigin;
        // Labels are bake-skipped; resolve only when dRays will display them.
        if (pConfig != null && pConfig.dRays) {
            this.lastMaterialLabel = branch.ensureMaterialLabel(world);
            if (virtualLod && this.lastMaterialLabel != null) {
                this.lastMaterialLabel = this.lastMaterialLabel + " lod" + cellSize;
            } else if (virtualLod) {
                this.lastMaterialLabel = "virtual lod" + cellSize;
            }
        } else {
            this.lastMaterialLabel = null;
        }
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

        VoxelShape shape = resolveShape(branch);

        // Sub-voxel shapes only at native 1³ cells; coarser frustum LOD uses the LOD cube.
        ShapeTraversal.Result geometry = emissionCast || cellSize > 1
                ? ShapeTraversal.Result.voxel()
                : ShapeTraversal.resolve(branch.start, branch.size, position, vector, shape, this::getStep);

        if (geometry.mode() == ShapeTraversal.Mode.SHAPE) {
            step = geometry.step();
            rstep = geometry.reflectStep();
            shapeEntryStep = geometry.entryStep();
            pdistance = geometry.permeationDistance();
            pposition = geometry.transmitPosition();
            rdistance = geometry.reflectDistance();
            rposition = geometry.reflectPosition();
        } else {
            if (emissionCast && originBlock != null) {
                cellBase = blockToVec(originBlock);
                cellSize = 1;
                this.lastBranchSize = 1;
            }
            step = getStep(cellBase, cellSize, position, vector);
            pdistance = step.step().length();
            pposition = exitPosition(position, step, cellBase, cellSize);
            if (emissionCast) {
                pposition = nudgeEmissionExit(pposition, vector);
            }
            rstep = step;
            rdistance = 0;
            rposition = position;
            gridAlignedReflect = true;
            if (!emissionCast
                    && cellSize == 1
                    && branch.size == 1
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
        Material branchMaterial = resolveMaterial(branch, BlockPos.ofFloored(normalized));
        Material interactionMaterial = interactionMaterialForCast(
                emissionCast, branchMaterial, shape, branch.start, position, geometry.mode()
        );

        // Emission segment (impeded unset): exit the origin cell as a full cube with no reflection
        // and no attenuation. Subsequent casts use real geometry and material interaction.
        // Snapshot once: branch.descriptor is a single volatile read, so every polar/impedance
        // check below (and the polarizedImpedance/polarContrast/polarBlendWeight calls) agree on
        // one baked generation instead of possibly re-reading mid-rebake.
        Branch.NodeDescriptor branchDescriptor = branch.descriptor;
        double newImpedance = interactionMaterial.impedance();
        if (!emissionCast && branchDescriptor.polar() != null
                && !Double.isNaN(branchDescriptor.mostCommonImpedance()) && !Double.isNaN(branchDescriptor.leastCommonImpedance())
                && branchDescriptor.mostCommonImpedance() != branchDescriptor.leastCommonImpedance()) {
            newImpedance = polarizedImpedance(branchDescriptor, vector);
        }
        if (!emissionCast && isVacuumImpedance(newImpedance)) {
            blank(position);
            return;
        }
        double priorImpedance = priorImpedanceForCast(impededSet, impeded, newImpedance);
        this.lastPriorImpedance = priorImpedance;
        boolean thinExit = !emissionCast && isThinMembraneExit(newImpedance);
        double reflectivity;
        double transmission;
        if (emissionCast) {
            Vec3i exitPlane = step.plane();
            if (shouldReflectAtEmissionExit(exitPlane)) {
                double targetImpedance = neighborImpedanceForExitPlane(exitPlane);
                this.lastPriorImpedance = emissionSourceImpedance;
                reflectivity = Physics.reflection(emissionSourceImpedance, targetImpedance);
                transmission = 1 - reflectivity;
            } else {
                reflectivity = 0;
                transmission = 1;
            }
        } else if (thinExit) {
            reflectivity = 0;
            transmission = transmissionForBoundary(0, interactionMaterial.permeation(), pdistance);
        } else {
            reflectivity = impedancesClose(priorImpedance, newImpedance)
                    ? 0
                    : Physics.reflection(priorImpedance, newImpedance);
            transmission = transmissionForBoundary(reflectivity, interactionMaterial.permeation(), pdistance);
        }

        // Polarized LOD cells: notable gate / commit can force reflect vs permeate.
        if (!emissionCast && branchDescriptor.polar() != null && reflectivity > 0) {
            double contrast = polarContrast(branchDescriptor);
            int splitsLeft = beamBudget.splitsRemaining();
            if (!Physics.isNotableInteraction(contrast, splitsLeft)) {
                // Not notable at this budget — continue without a hard event (permeate).
                reflectivity = 0;
                transmission = transmissionForBoundary(0, interactionMaterial.permeation(), pdistance);
            } else if (splitsLeft <= 0) {
                double w = polarBlendWeight(branchDescriptor, vector);
                if (!Physics.commitReflect(w)) {
                    reflectivity = 0;
                    transmission = transmissionForBoundary(0, interactionMaterial.permeation(), pdistance);
                    Vec3d bent = Physics.permeationBend(vector, vector.normalize(), branchDescriptor.polar().normalize());
                    // Replace transmit direction below when bent.
                    vector = bent;
                }
            }
        }

        boolean shapeMode = geometry.mode() == ShapeTraversal.Mode.SHAPE;
        this.lastShapeMode = shapeMode;
        Vec3i transmitPlane = shapeMode ? rstep.plane() : step.plane();
        Vec3i reflectPlane;
        if (gridAlignedReflect) {
            if (emissionCast && reflectivity > 0) {
                reflectPlane = step.plane();
            } else {
                reflectPlane = entryPlane(position, cellBase, cellSize, vector);
            }
        } else if (shapeMode && shapeEntryStep != null) {
            reflectPlane = shapeEntryStep.plane();
        } else {
            reflectPlane = rstep.plane();
        }
        // Open-cell exit: survey N/E/D from the first+second DDA axes when they differ. Same-axis
        // projection → ordinary non-NED boundary (no forward survey).
        FrustumLod.Interaction forwardInteraction = FrustumLod.Interaction.CORNER;
        if (!emissionCast
                && cellSize > 1
                && gridAlignedReflect
                && step.plane() != Vec3i.ZERO
                && !isSolidImpedance(newImpedance)) {
            ForwardSurvey survey = surveyForward(
                    cellOrigin, cellBase, cellSize, pposition, step.plane(), vector, newImpedance);
            if (survey == null) {
                forwardInteraction = FrustumLod.Interaction.FACE;
            } else {
                forwardInteraction = survey.interaction();
                if (forwardInteraction == FrustumLod.Interaction.GAP) {
                    reflectivity = 0;
                    transmission = transmissionForBoundary(0, interactionMaterial.permeation(), pdistance);
                } else {
                    if (reflectivity == 0) {
                        double wallZ = survey.wallImpedance();
                        if (Double.isFinite(wallZ) && !impedancesClose(priorImpedance, wallZ)) {
                            reflectivity = Physics.reflection(priorImpedance, wallZ);
                            transmission = transmissionForBoundary(
                                    reflectivity, interactionMaterial.permeation(), pdistance);
                        }
                    }
                    if (reflectivity > 0) {
                        // Bounce off the exit face from the open side (SVG H cell), not the entry face.
                        reflectPlane = step.plane();
                        rposition = pposition;
                        rdistance = pdistance;
                    }
                }
            }
        }

        @Nullable Vec3d reflectedDir = reflectivity > 0 ? Physics.pseudoReflect(vector, reflectPlane) : null;
        @Nullable Vec3d transmitted = emissionCast
                ? vector
                : Physics.pseudoReflect(vector, transmitPlane, transmission / 5);
        Vec3d reflectStart = gridAlignedReflect ? rposition : nudgeReflectOrigin(rposition, reflectedDir, rdistance);
        if (!emissionCast && reflectivity > 0 && gridAlignedReflect && reflectPlane != Vec3i.ZERO) {
            // CORNER/SPLIT walk to the shared vertex; FACE/GAP stay on the hit face.
            reflectStart = FrustumLod.edgeWalk(
                    reflectStart, cellBase, cellSize, reflectPlane, vector, 0.85, forwardInteraction);
            rdistance = reflectStart.subtract(position).length();
        }
        // } */
        // apply movement
        reflect(reflectivity * power, reflectStart, reflectedDir, rdistance);
        transmit(transmission * power, pposition, transmitted, pdistance);
        stood = step;
        this.lastPermeationDistance = pdistance;
        this.lastReflectivity = reflectivity;
        this.lastTransmission = transmission;
        this.lastBoundaryResolved = true;
        this.lastPolarAlignment = (branchDescriptor.polar() != null && branchDescriptor.polar().lengthSquared() > 1e-12)
                ? Physics.polarAlignment(vector.normalize(), branchDescriptor.polar().normalize())
                : 1.0;
        this.lastMaterial = interactionMaterial;
    }

    /** Combined result of {@link #surveyForward}: the four-map interaction classification plus
     *  the stiffest finite neighbor impedance found during that same survey (or {@link Double#NaN}
     *  when no neighbor resolved to a finite impedance). */
    private record ForwardSurvey(FrustumLod.Interaction interaction, double wallImpedance) {}

    /**
     * Surveys N/E/D at the same LOD as {@code cellOrigin} in one pass, returning both the
     * four-map interaction (host→neighbor interaction permeate, {@link FrustumLod#blocksPermeation},
     * not raw impedance stiffness; missing neighbors count as open) and the wall impedance
     * ({@code max} of the finite neighbor impedances) needed to resolve a boundary that turned out
     * non-reflective. Returns {@code null} when the DDA projection says this exit is a single-axis
     * (non-NED) step.
     */
    private @Nullable ForwardSurvey surveyForward(
            BlockPos cellOrigin,
            Vec3d cellBase,
            int cellSize,
            Vec3d hitPos,
            Vec3i face,
            Vec3d rayDir,
            double hostImpedance
    ) {
        FrustumLod.ForwardMap map = FrustumLod.forwardMap(cellBase, cellSize, hitPos, rayDir, face);
        if (map == null) {
            return null;
        }

        Branch nBranch = lodAt(cellOrigin.add(map.nOffset().getX(), map.nOffset().getY(), map.nOffset().getZ()), cellSize);
        NeighborSample n = sampleNeighbor(nBranch);
        boolean nBlocks = FrustumLod.blocksPermeation(hostImpedance, n.impedance(), n.permeation());

        NeighborSample e = NeighborSample.MISSING;
        NeighborSample d = n;
        boolean eBlocks = false;
        boolean dBlocks = nBlocks;
        if (map.hasTangent()) {
            Branch eBranch = lodAt(cellOrigin.add(map.eOffset().getX(), map.eOffset().getY(), map.eOffset().getZ()), cellSize);
            e = sampleNeighbor(eBranch);
            eBlocks = FrustumLod.blocksPermeation(hostImpedance, e.impedance(), e.permeation());

            Branch dBranch = lodAt(cellOrigin.add(map.dOffset().getX(), map.dOffset().getY(), map.dOffset().getZ()), cellSize);
            d = sampleNeighbor(dBranch);
            dBlocks = FrustumLod.blocksPermeation(hostImpedance, d.impedance(), d.permeation());
        }

        FrustumLod.Interaction interaction = FrustumLod.classify(nBlocks, eBlocks, dBlocks);

        double wall = Double.NEGATIVE_INFINITY;
        if (Double.isFinite(n.impedance())) wall = Math.max(wall, n.impedance());
        if (Double.isFinite(e.impedance())) wall = Math.max(wall, e.impedance());
        if (Double.isFinite(d.impedance())) wall = Math.max(wall, d.impedance());
        double wallImpedance = wall == Double.NEGATIVE_INFINITY ? Double.NaN : wall;

        return new ForwardSurvey(interaction, wallImpedance);
    }

    /** One neighbor's impedance + permeation, sampled from a single {@code descriptor}/{@code
     *  material} read each so both values come from the same baked generation — reading them via
     *  two separate accessor calls let a concurrent rebake land in between, mixing an old
     *  impedance with a new permeation (or vice versa) for the same neighbor. */
    private record NeighborSample(double impedance, double permeation) {
        static final NeighborSample MISSING = new NeighborSample(Double.NaN, Double.NaN);
    }

    private static NeighborSample sampleNeighbor(@Nullable Branch b) {
        if (b == null) {
            return NeighborSample.MISSING;
        }
        // One descriptor read + one material read, reused for both impedance and permeation —
        // two independent Branch.effectiveImpedance()/effectivePermeation() calls would each read
        // `material` on their own and could straddle a concurrent rebake.
        Branch.NodeDescriptor d = b.descriptor;
        Material material = b.material;
        double impedance = !Double.isNaN(d.avgImpedance()) ? d.avgImpedance()
                : !Double.isNaN(d.mostCommonImpedance()) ? d.mostCommonImpedance()
                : material != null ? material.impedance() : Double.NaN;
        double permeation = material != null ? material.permeation() : 1.0;
        return new NeighborSample(impedance, permeation);
    }

    private @Nullable Branch lodAt(BlockPos pos, int lod) {
        if (chunk != null) {
            ChunkChain c = chunk.access(pos.getX() >> 4, pos.getZ() >> 4);
            if (c != null) {
                Branch root = c.getBranch(pos.getY() >> 4);
                if (root != null) {
                    return root.getAtLod(pos, lod);
                }
            }
        }
        return tree == null ? null : tree.getAtLod(pos, lod);
    }

    private static double polarizedImpedance(Branch.NodeDescriptor descriptor, Vec3d vector) {
        double w = polarBlendWeight(descriptor, vector);
        return Physics.blendImpedance(descriptor.mostCommonImpedance(), descriptor.leastCommonImpedance(), w);
    }

    private static double polarBlendWeight(Branch.NodeDescriptor descriptor, Vec3d vector) {
        Vec3d pol = descriptor.polar();
        if (pol == null || pol.lengthSquared() < 1e-12) {
            return 0.5;
        }
        double blend = descriptor.blendCoefficient();
        double s = !Double.isNaN(blend) && blend >= 0.75 ? 2.0 : 1.0;
        return Physics.polarBlendWeight(Physics.polarAlignment(vector.normalize(), pol.normalize()), s);
    }

    private static double polarContrast(Branch.NodeDescriptor descriptor) {
        double most = descriptor.mostCommonImpedance();
        double least = descriptor.leastCommonImpedance();
        double denom = Math.max(Math.abs(most), Math.abs(least));
        if (denom < 1e-9 || Double.isNaN(denom)) {
            return 0.0;
        }
        return Math.abs(most - least) / denom;
    }

    /**
     * Prior medium for boundary physics. An unset {@code impeded} means the ray is being born in
     * {@code mediumImpedance}, so {@code unset:air} and {@code unset:stone} behave like matched pairs.
     * Entering vacuum ({@code stone:null}) is handled separately and must not use this shortcut.
     */
    static double priorImpedanceForCast(boolean impededSet, double impeded, double mediumImpedance) {
        return impededSet ? impeded : mediumImpedance;
    }

    /**
     * Samples the six face neighbors of {@link #originBlock} and the born-in medium impedance
     * before any rays launch, so the first emission exit can reflect into stiffer neighbors
     * instead of permeating freely.
     */
    public void prepareEmissionContext(Vec3d soundPos) {
        emissionNeighborImpedances = new double[FACE_OFFSETS.length];
        emissionNeighborAverageImpedance = Double.NaN;
        emissionSourceImpedance = Double.NaN;
        if (originBlock == null || world == null || chunk == null) {
            return;
        }

        chunk = chunk.access(originBlock.getX() >> 4, originBlock.getZ() >> 4);
        if (chunk == null) {
            return;
        }

        BlockState originState = ((WorldChunk) chunk).getBlockState(originBlock);
        VoxelShape originShape = originState.getCollisionShape(world, originBlock);
        Material originMaterial = material(originState);
        emissionSourceImpedance = interactionMaterial(
                originMaterial, originShape, originBlock, soundPos, ShapeTraversal.Mode.VOXEL
        ).impedance();

        double sum = 0.0;
        for (int i = 0; i < FACE_OFFSETS.length; i++) {
            double neighborImpedance = impedanceAtBlock(originBlock.add(FACE_OFFSETS[i]));
            emissionNeighborImpedances[i] = neighborImpedance;
            sum += neighborImpedance;
        }
        emissionNeighborAverageImpedance = sum / FACE_OFFSETS.length;
    }

    static BlockPos neighborOffsetForExitPlane(Vec3i plane) {
        return new BlockPos(-plane.getX(), -plane.getY(), -plane.getZ());
    }

    static boolean shouldReflectAtEmissionExit(double targetImpedance, double neighborAverage, double sourceImpedance) {
        return Double.isFinite(targetImpedance)
                && Double.isFinite(neighborAverage)
                && Double.isFinite(sourceImpedance)
                && targetImpedance > neighborAverage
                && targetImpedance > sourceImpedance;
    }

    boolean shouldReflectAtEmissionExit(Vec3i exitPlane) {
        return shouldReflectAtEmissionExit(
                neighborImpedanceForExitPlane(exitPlane),
                emissionNeighborAverageImpedance,
                emissionSourceImpedance
        );
    }

    double neighborImpedanceForExitPlane(Vec3i plane) {
        if (emissionNeighborImpedances == null) {
            return Double.NaN;
        }
        BlockPos offset = neighborOffsetForExitPlane(plane);
        for (int i = 0; i < FACE_OFFSETS.length; i++) {
            if (FACE_OFFSETS[i].equals(offset)) {
                return emissionNeighborImpedances[i];
            }
        }
        return Double.NaN;
    }

    private double impedanceAtBlock(BlockPos pos) {
        if (chunk == null || world == null) {
            return MaterialRegistry.DEFAULT.impedance();
        }
        chunk = chunk.access(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return MaterialRegistry.DEFAULT.impedance();
        }
        BlockState state = ((WorldChunk) chunk).getBlockState(pos);
        return material(state).impedance();
    }

    static boolean isVacuumImpedance(double impedance) {
        return impedance <= 0.0 || !Double.isFinite(impedance);
    }

    static double emissionCellExitDistance(Vec3d cellOrigin, Vec3d position, Vec3d vector) {
        Pair<Double, Vec3i> pair = getStepPair(cellOrigin, 1, position, vector);
        return pair.getLeft() * vector.length();
    }

    static Vec3d nudgeEmissionExit(Vec3d exitPosition, Vec3d vector) {
        // TODO(perf): profile sqrt (length()) vs. the branch before switching this guard to
        // lengthSquared() < 1e-12*1e-12 to skip the sqrt on the common non-degenerate path.
        double length = vector.length();
        if (length < 1e-12) {
            return exitPosition;
        }
        return exitPosition.add(vector.multiply(EMISSION_EXIT_NUDGE / length));
    }

    /**
     * Partial solids (doors, panes, etc.) can contain open air inside the 1³ cell. When the ray
     * is in that air — whether on the first cast or while permeating through the cell — use air
     * impedance so it can voxel-step out instead of reflecting off interior geometry. Inside solid
     * sub-voxel geometry (SHAPE mode or a point inside the collision boxes) keeps the block
     * material.
     */
    static Material interactionMaterialForCast(
            boolean emissionCast,
            Material branchMaterial,
            VoxelShape shape,
            BlockPos origin,
            Vec3d position,
            ShapeTraversal.Mode mode
    ) {
        if (emissionCast) {
            // Cube exit geometry, but the born-in medium still follows position (air gap vs solid).
            return interactionMaterial(branchMaterial, shape, origin, position, ShapeTraversal.Mode.VOXEL);
        }
        return interactionMaterial(branchMaterial, shape, origin, position, mode);
    }

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
        // TODO(perf): profile sqrt (length()) vs. the branch before switching this guard to
        // lengthSquared() <= REFLECT_NUDGE*REFLECT_NUDGE to skip the sqrt on the common path.
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

    /**
     * Transmission after traversing {@code distance} blocks, including reflection loss and
     * permeation attenuation ({@code permeation^distance}).
     */
    static double transmissionForBoundary(double reflectivity, double permeation, double distance) {
        double permeationFactor = Acoustics.permeationOverDistance(permeation, distance);
        return (1 - reflectivity) * permeationFactor;
    }

    static double transmissionForBoundary(
            double priorImpedance,
            double newImpedance,
            double permeation,
            double distance
    ) {
        double reflectivity = impedancesClose(priorImpedance, newImpedance)
                ? 0
                : Physics.reflection(priorImpedance, newImpedance);
        return transmissionForBoundary(reflectivity, permeation, distance);
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
        if (!impededSet || !isSolidImpedance(impeded)) {
            return false;
        }
        return impedancesClose(newImpedance, enteredFrom);
    }

    /** Commits the entered branch impedance after the caller chooses permeation (not reflection). */
    public void commitPermeation() {
        if (lastMaterial == null) {
            return;
        }
        commitEnteredImpedance(lastMaterial.impedance(), lastPermeationDistance);
    }

    /**
     * After the emission segment leaves the origin cell, adopt the impedance of the medium the ray
     * actually entered — not the block it was born in. Without this, a ray exiting stone into air
     * keeps stone as prior and reflects fully at the first air cell face.
     *
     * @return false when the exit cell is unknown or vacuum (ray is blanked)
     */
    public boolean commitEmissionExit(Vec3d exitPosition, Vec3d direction) {
        Material exitMedium = interactionMaterialAt(exitPosition, direction);
        if (exitMedium == null || isVacuumImpedance(exitMedium.impedance())) {
            blank(exitPosition);
            return false;
        }
        impeded = exitMedium.impedance();
        impededSet = true;
        enteredFrom = null;
        solidTransitDistance = 0.0;
        return true;
    }

    private void commitEnteredImpedance(double newImpedance, double step) {
        if (impededSet && enteredFrom == null
                && isSolidImpedance(newImpedance) && !isSolidImpedance(impeded)) {
            enteredFrom = impeded;
            solidTransitDistance = 0.0;
        }

        if (isSolidImpedance(newImpedance)) {
            solidTransitDistance += step;
        }

        impeded = newImpedance;
        impededSet = true;

        if (enteredFrom != null && impedancesClose(newImpedance, enteredFrom)) {
            enteredFrom = null;
            solidTransitDistance = 0.0;
        }
    }

    @Nullable
    private Material interactionMaterialAt(Vec3d position, Vec3d direction) {
        if (world == null) {
            return null;
        }
        Vec3d normalized = normalize(position, direction);
        if (chunk != null) {
            chunk = chunk.access((int) normalized.x >> 4, (int) normalized.z >> 4);
            if (chunk != null) {
                tree = chunk.getBranch((int) normalized.y >> 4);
            }
        }
        Branch branch = getBlock(normalized);
        if (branch == null) {
            return null;
        }
        VoxelShape shape = resolveShape(branch);
        Material branchMaterial = resolveMaterial(branch, BlockPos.ofFloored(normalized));
        return interactionMaterial(
                branchMaterial, shape, branch.start, position, ShapeTraversal.Mode.VOXEL
        );
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
            return xUnit(vector.x);
        }
        if (position.y == base.y || position.y == base.y + size) {
            return yUnit(vector.y);
        }
        if (position.z == base.z || position.z == base.z + size) {
            return zUnit(vector.z);
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
        if (pConfig != null && pConfig.dRays) {
            leaf.materialLabel = MaterialRegistry.describe(state);
        }
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
        double xstep = boundAxis(base.x, position.x, size, vector.x);
        double ystep = boundAxis(base.y, position.y, size, vector.y);
        double zstep = boundAxis(base.z, position.z, size, vector.z);

        // Argmin over the three axis coefficients via ternaries (no allocation in either branch),
        // so the JIT can compile this to a conditional move; the winning axis's Vec3i is resolved
        // via the cached flyweights (xUnit/yUnit/zUnit) only once the argmin is known, instead of
        // allocating a candidate Vec3i per comparison.
        boolean yWins = ystep < xstep;
        double coefficient = yWins ? ystep : xstep;
        int axis = yWins ? 1 : 0;

        boolean zWins = zstep < coefficient;
        coefficient = zWins ? zstep : coefficient;
        axis = zWins ? 2 : axis;

        Vec3i planarIndex = switch (axis) {
            case 0 -> xUnit(vector.x);
            case 1 -> yUnit(vector.y);
            default -> zUnit(vector.z);
        };

		if (coefficient == Double.POSITIVE_INFINITY) {
			LOGGER.warn(
					"invalid coefficient (no forward face hit) base={} size={} pos={} dir={}",
					base, size, position, vector
			);
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

    /**
     * Advances {@link #frustumSize} through one boundary. Permeation applies additive growth
     * ({@code += distance * growthPerBlock}) before the energy/alignment shrink; reflection
     * skips growth and only shrinks.
     *
     * @param permeated {@code true} when continuing along the transmitted leg; {@code false} on reflect
     */
    public double applyFrustumStep(
            double stepDistance,
            double growthPerBlock,
            double leftoverEnergyCoefficient,
            boolean permeated
    ) {
        double growth = permeated ? growthPerBlock : 0.0;
        frustumSize = FrustumLod.nextFrustumSize(
                frustumSize, stepDistance, growth, lastPolarAlignment, leftoverEnergyCoefficient);
        return frustumSize;
    }

    /**
     * Virtual frustum step size under the linear (non-compounding) footprint model:
     * {@code min(branchSize, }{@link FrustumLod#stepForDistance}{@code )}. The live cast tracks real
     * per-interaction {@link #frustumSize} instead (see {@link #applyFrustumStep}); this
     * remains for debug/test contexts that only preview footprint growth over free travel.
     */
    public static int effectiveStepSize(int branchSize, double distance, double growthPerBlock) {
        return FrustumLod.effectiveStepSize(branchSize, distance, growthPerBlock);
    }
}
