package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.material.Acoustics;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * LOD frustum growth, step schedule, and the forward N/E/D occupancy map used at an open-cell
 * exit.
 *
 * <p>Footprint width {@code = }{@link #BASE_FOOTPRINT}{@code + growthPerBlock * distance}, where
 * {@code growthPerBlock} comes from {@link #growthPerBlock(int)} — never a hardcoded constant, since
 * the correct rate depends on how many rays share the sphere (see below). Step sizes follow
 * {@code 1,1,2,2,4,4,8,8,16,16} then stay capped at {@link #MAX_STEP}.
 *
 * <p>{@code growthPerBlock} is derived, not tuned by hand: {@code nRays} beams are spread over the
 * full sphere ({@code 4π} steradians, see {@code Engine.updateRays}' golden-angle spiral, which
 * is equal-area so every beam owns on average {@code 4π/nRays} steradians regardless of it being
 * one continuous spiral rather than a per-axis sweep). Modeling each beam's cross-section as a
 * square pyramidal frustum (matching the square N/E/D octree grid, not a circular cone) with equal
 * half-angle {@code θ} on both axes gives solid angle {@code Ω = 4*asin(sin²θ)}; setting
 * {@code Ω = 4π/nRays} and solving for the footprint's linear diameter-growth-per-block
 * ({@code 2*tanθ}) yields {@link #growthPerBlock(int)}. Callers must fetch this once from
 * {@code PrecomputedConfig.pConfig.frustumGrowthPerBlock} (cached whenever {@code nRays} changes)
 * rather than calling {@link #growthPerBlock(int)} per-cast — it involves asin/sin/tan and has no
 * business running inside the raycast hot path.
 *
 * <p>N/E/D axes come from the next two octant-sized DDA boundaries the ray will strike (signed
 * travel directions), not from “face plane + dominant tangent”. The first boundary is the exit
 * just resolved; the second is projected by continuing Amanatides–Woo arithmetic one same-size
 * cell forward <em>without</em> fetching that neighbor from the octree. If that projected face
 * shares the first face's axis, the exit is an ordinary single-axis DDA step (no N/E/D survey).
 * Otherwise {@code N} is the first axis, {@code E} the second, and {@code D = N+E}. Trailing cells
 * are never surveyed. Four maps (same heading):
 * <pre>
 *   N | D          1 CORNER  X X / O X  reflect, walk to the shared vertex
 *  ---+---         2 GAP     X O / O O  pass through E/D
 *   H | E          3 SPLIT   X O / O X  both (1) and (2) as child beams
 *                  4 FACE    X X / O O  walk to D–E-only vertex, then reflect
 * </pre>
 */
public final class FrustumLod {
    private FrustumLod() {}

    public static final double BASE_FOOTPRINT = 1.0;
    public static final int MAX_STEP = 16;

    /**
     * Linear footprint-diameter growth per block of travel for {@code nRays} beams tiling the full
     * sphere with no gaps (square-pyramidal-frustum model). See the class doc for the derivation.
     * Pure math — cheap enough on its own, but callers should still read the cached
     * {@code PrecomputedConfig.pConfig.frustumGrowthPerBlock} instead of calling this per-cast; it
     * only needs recomputing when {@code nRays} (a config value) changes.
     */
    public static double growthPerBlock(int nRays) {
        double n = Math.max(1, nRays);
        double solidAnglePerRay = 4.0 * Math.PI / n;
        double halfAngle = Math.asin(Math.sqrt(Math.sin(solidAnglePerRay / 4.0)));
        return 2.0 * Math.tan(halfAngle);
    }

    static final double AXIS_EPS = 1e-9;

    /**
     * Outcome of the N/E/D blocking map (host→neighbor interaction permeation). {@link #SPLIT}
     * reuses {@link #CORNER} (reflect + vertex walk) and {@link #GAP} (pass) as the two child shapes.
     */
    public enum Interaction {
        CORNER,
        GAP,
        SPLIT,
        FACE
    }

    /**
     * Same-size neighbor offsets from {@code H}: {@code N} along the first DDA exit axis,
     * {@code E} along the projected second DDA axis, {@code D = N+E}.
     */
    public record ForwardMap(
            @NotNull Vec3i nOffset,
            @NotNull Vec3i eOffset,
            @NotNull Vec3i dOffset,
            int faceAxis,
            int tangentAxis
    ) {
        public boolean hasTangent() {
            return eOffset.getX() != 0 || eOffset.getY() != 0 || eOffset.getZ() != 0;
        }
    }

    public static double footprintAt(double distance, double growthPerBlock) {
        return BASE_FOOTPRINT + growthPerBlock * Math.max(0.0, distance);
    }

    /**
     * Next {@code frustumSize} after traveling {@code stepDistance} blocks through one boundary
     * interaction. Growth and shrink are separate:
     * <pre>
     *   grown = previousSize + growthPerBlock * stepDistance
     *   next  = lerp(min: leftoverEnergy², max: 1, t: polarAlignment) * grown
     * </pre>
     * Pass {@code growthPerBlock = 0} on reflect (no growth). On permeate, pass the nominal rate so
     * growth is applied before the energy/alignment shrink coefficient.
     *
     * <p>{@code polarAlignment} is the raw {@code (rayNorm·polNorm)²} alignment at the boundary just
     * resolved (1.0 when the boundary had no polarization vector — a clean, unambiguous interface
     * imposes no throttle). {@code leftoverEnergyCoefficient} is whichever of reflectivity/
     * transmission actually carried the beam forward through that boundary (only one applies per
     * step: the beam either reflected or transmitted); it is squared before blending so shrink grows
     * faster than the raw coefficient falls off — a beam that only carries half its energy forward
     * loses three-quarters of its footprint headroom, not half. Full alignment leaves the (grown)
     * footprint unchanged by energy loss; poor alignment throttles toward the surviving energy
     * fraction.
     *
     * <p>This is the only place {@code frustumSize} advances in the cast process outside of its
     * initial value — callers hold the running size on their own state (e.g. {@code Cast.frustumSize})
     * and feed it back in as {@code previousSize} each step.
     */
    public static double nextFrustumSize(
            double previousSize,
            double stepDistance,
            double growthPerBlock,
            double polarAlignment,
            double leftoverEnergyCoefficient
    ) {
        double grown = previousSize + growthPerBlock * stepDistance;
        double leftoverEnergySquared = leftoverEnergyCoefficient * leftoverEnergyCoefficient;
        double blend = leftoverEnergySquared + (1.0 - leftoverEnergySquared) * polarAlignment;
        return blend * grown;
    }

    /**
     * Whether growing the frustum to {@code candidateSize} from {@code exitPos} would double-cover
     * space already swept past the polarity mid-plane (solid half). Used to defer growth and trigger
     * a free graze-refraction instead. Stub — always {@code false} until Task E lands.
     */
    public static boolean wouldDoubleCover(
            Vec3d cellBase, int cellSize, Vec3d exitPos,
            @Nullable Vec3d polar, double candidateSize, double currentSize
    ) {
        return false;
    }

    /**
     * LOD step for a footprint width: schedule {@code 1,1,2,2,4,4,8,8,16,16,…} capped at
     * {@link #MAX_STEP}. Derived from footprint slots {@code floor(footprint - BASE)}.
     */
    public static int stepForSize(double footprint) {
        if (footprint < BASE_FOOTPRINT) {
            return 1;
        }
        int slot = (int) Math.floor(footprint - BASE_FOOTPRINT);
        int shift = Math.min(4, slot / 2);
        return 1 << shift;
    }

    /**
     * LOD step for a traveled {@code distance} under linear (non-compounding) footprint growth:
     * schedule {@code 1,1,2,2,4,4,8,8,16,16,…} capped at {@link #MAX_STEP}. Used by contexts that
     * only preview footprint growth over free travel (e.g. the debug beam walker) rather than
     * tracking real per-interaction {@code frustumSize} state — see {@link #nextFrustumSize} for
     * the stateful per-step model used by the live cast.
     *
     * @param growthPerBlock precomputed rate, e.g. {@code PrecomputedConfig.pConfig.frustumGrowthPerBlock}
     */
    public static int stepForDistance(double distance, double growthPerBlock) {
        return stepForSize(footprintAt(distance, growthPerBlock));
    }

    /**
     * Step size used inside a concrete octree node: {@code min(branchSize, stepForDistance)}, so a
     * real fine leaf still wins when the tree has subdivided below the frustum LOD.
     *
     * @param growthPerBlock precomputed rate, e.g. {@code PrecomputedConfig.pConfig.frustumGrowthPerBlock}
     */
    public static int effectiveStepSize(int branchSize, double distance, double growthPerBlock) {
        int branch = Math.max(1, branchSize);
        return Math.min(branch, stepForDistance(distance, growthPerBlock));
    }

    /** Align {@code pos} onto the {@code step}-grid that tiles from {@code leafStart}. */
    public static @NotNull BlockPos alignOrigin(@NotNull BlockPos pos, @NotNull BlockPos leafStart, int step) {
        int s = Math.max(1, step);
        int x = leafStart.getX() + Math.floorDiv(pos.getX() - leafStart.getX(), s) * s;
        int y = leafStart.getY() + Math.floorDiv(pos.getY() - leafStart.getY(), s) * s;
        int z = leafStart.getZ() + Math.floorDiv(pos.getZ() - leafStart.getZ(), s) * s;
        return new BlockPos(x, y, z);
    }

    /**
     * Unit axis of the hit face pointing with the ray (forward through the face). Cast stores
     * {@code plane = -sign(ray)} on the crossed axis; this recovers the travel sign.
     */
    public static @NotNull Vec3i forwardFace(@NotNull Vec3i face, @NotNull Vec3d rayDir) {
        int axis = faceAxis(face);
        int s = axisSign(component(rayDir, axis));
        if (s == 0) {
            int f = axis == 0 ? face.getX() : axis == 1 ? face.getY() : face.getZ();
            s = -Integer.signum(f);
            if (s == 0) {
                s = 1;
            }
        }
        return axisUnit(axis, s);
    }

    /**
     * Builds the N/E/D map from the first exit plane and the next same-size DDA face the ray would
     * strike after crossing it. The second face is pure arithmetic (virtual neighbor cell of
     * {@code cellSize}) — no octree fetch. Returns {@code null} when that projected face shares
     * {@code firstPlane}'s axis (ordinary non-NED DDA boundary).
     *
     * @param cellBase   min corner of H
     * @param hitPos     position on the first exit face
     * @param firstPlane Cast plane ({@code -sign(ray)} on the crossed axis)
     */
    public static @Nullable ForwardMap forwardMap(
            @NotNull Vec3d cellBase,
            int cellSize,
            @NotNull Vec3d hitPos,
            @NotNull Vec3d rayDir,
            @NotNull Vec3i firstPlane
    ) {
        if (firstPlane.equals(Vec3i.ZERO)) {
            return null;
        }
        int s = Math.max(1, cellSize);
        int nAxis = faceAxis(firstPlane);
        int nSign = travelSign(firstPlane, rayDir, nAxis);
        Vec3i secondPlane = nextDdaPlane(cellBase, s, hitPos, rayDir, nAxis, nSign);
        if (secondPlane == null || secondPlane.equals(Vec3i.ZERO)) {
            return null;
        }
        int eAxis = faceAxis(secondPlane);
        if (eAxis == nAxis) {
            return null;
        }
        int eSign = travelSign(secondPlane, rayDir, eAxis);
        if (eSign == 0) {
            return null;
        }
        Vec3i n = axisUnit(nAxis, nSign * s);
        Vec3i e = axisUnit(eAxis, eSign * s);
        Vec3i d = new Vec3i(n.getX() + e.getX(), n.getY() + e.getY(), n.getZ() + e.getZ());
        return new ForwardMap(n, e, d, nAxis, eAxis);
    }

    /**
     * Next face plane the ray would exit after entering the same-size neighbor beyond
     * {@code firstAxis}/{@code firstTravelSign}. Does not sample the octree — only assumes a
     * {@code cellSize} cube abutting H. Plane uses Cast's {@code -sign(ray)} convention.
     */
    public static @Nullable Vec3i nextDdaPlane(
            @NotNull Vec3d cellBase,
            int cellSize,
            @NotNull Vec3d hitPos,
            @NotNull Vec3d rayDir,
            int firstAxis,
            int firstTravelSign
    ) {
        int s = Math.max(1, cellSize);
        if (firstTravelSign == 0) {
            return null;
        }
        double nextX = cellBase.x + (firstAxis == 0 ? firstTravelSign * s : 0);
        double nextY = cellBase.y + (firstAxis == 1 ? firstTravelSign * s : 0);
        double nextZ = cellBase.z + (firstAxis == 2 ? firstTravelSign * s : 0);
        return ddaExitPlane(nextX, nextY, nextZ, s, hitPos, rayDir);
    }

    /**
     * Argmin axis exit from an AABB, matching {@code Cast.getStepPair}'s bound/tie rules. Plane
     * components are {@code -sign(dir)} on the winning axis.
     */
    static @Nullable Vec3i ddaExitPlane(
            double baseX, double baseY, double baseZ,
            int size,
            @NotNull Vec3d position,
            @NotNull Vec3d vector
    ) {
        double xstep = boundAxis(baseX, position.x, size, vector.x);
        double ystep = boundAxis(baseY, position.y, size, vector.y);
        double zstep = boundAxis(baseZ, position.z, size, vector.z);

        boolean yWins = ystep < xstep;
        double coefficient = yWins ? ystep : xstep;
        int axis = yWins ? 1 : 0;

        boolean zWins = zstep < coefficient;
        coefficient = zWins ? zstep : coefficient;
        axis = zWins ? 2 : axis;

        if (!(coefficient > 0) || !Double.isFinite(coefficient)) {
            return null;
        }
        double dir = axis == 0 ? vector.x : axis == 1 ? vector.y : vector.z;
        int planeSign = dir > 0 ? -1 : dir < 0 ? 1 : 0;
        if (planeSign == 0) {
            return null;
        }
        return axisUnit(axis, planeSign);
    }

    /** Same positive-forward bound as {@code Cast.boundAxis}. */
    static double boundAxis(double base, double pos, double size, double dir) {
        double value = (base - pos + (dir > 0 ? size : 0)) / dir;
        if (value <= 0 || Double.isNaN(value)) {
            return Double.POSITIVE_INFINITY;
        }
        return value;
    }

    private static int faceAxis(@NotNull Vec3i face) {
        if (face.getX() != 0) {
            return 0;
        }
        if (face.getY() != 0) {
            return 1;
        }
        return 2;
    }

    private static int travelSign(@NotNull Vec3i plane, @NotNull Vec3d rayDir, int axis) {
        int s = axisSign(component(rayDir, axis));
        if (s != 0) {
            return s;
        }
        int f = axis == 0 ? plane.getX() : axis == 1 ? plane.getY() : plane.getZ();
        s = -Integer.signum(f);
        return s == 0 ? 1 : s;
    }

    /**
     * Four-map classifier. {@code n}/{@code e}/{@code d} are whether the host→neighbor interaction
     * blocks (low permeate — see {@link #blocksPermeation}). Head-on (no {@code E}) is
     * {@link Interaction#FACE} when {@code N} blocks, else {@link Interaction#GAP}.
     *
     * <p>When {@code N} is open, the exit is always {@link Interaction#GAP} — side walls on
     * {@code E}/{@code D} must not invent a face reflection through an open exit (that was
     * counting matched air:air hosts as R=1).
     */
    public static @NotNull Interaction classify(boolean nBlocks, boolean eBlocks, boolean dBlocks) {
        if (nBlocks && eBlocks && dBlocks) {
            return Interaction.CORNER;
        }
        if (nBlocks && eBlocks) {
            return Interaction.SPLIT;
        }
        if (nBlocks && dBlocks) {
            return Interaction.FACE;
        }
        // N open, or N alone blocks with E/D open: transmit through the exit (GAP).
        return Interaction.GAP;
    }

    /**
     * Host→neighbor interaction permeate: {@code (1 - R(host, neighbor)) * neighborPermeation}.
     * Missing/non-finite neighbor impedance → fully open ({@code 1}). Non-finite neighbor
     * permeation defaults to {@code 1} so impedance mismatch alone still drives the result.
     */
    public static double interactionPermeation(double hostZ, double neighborZ, double neighborPermeation) {
        if (!Double.isFinite(neighborZ) || !Double.isFinite(hostZ)) {
            return 1.0;
        }
        double r = Acoustics.reflection(hostZ, neighborZ);
        double perm = Double.isFinite(neighborPermeation) ? neighborPermeation : 1.0;
        return (1.0 - r) * perm;
    }

    /**
     * Neighbor blocks the forward map when host→neighbor interaction permeate is below
     * {@link #PERMEATION_BLOCK_THRESHOLD} (wall-like — reflect/edge-walk rather than phase through).
     */
    public static final double PERMEATION_BLOCK_THRESHOLD = 0.5;

    public static boolean blocksPermeation(double hostZ, double neighborZ, double neighborPermeation) {
        return interactionPermeation(hostZ, neighborZ, neighborPermeation) < PERMEATION_BLOCK_THRESHOLD;
    }

    /**
     * @deprecated impedance-only stiffness; forward-map survey uses {@link #blocksPermeation}.
     */
    @Deprecated
    public static boolean isStiff(double neighborZ, double hostZ) {
        if (!Double.isFinite(neighborZ)) {
            return false;
        }
        if (!Double.isFinite(hostZ)) {
            return neighborZ >= 10_000.0;
        }
        if (neighborZ <= hostZ) {
            return false;
        }
        double max = Math.max(Math.abs(neighborZ), Math.abs(hostZ));
        return max >= 1e-6 && (neighborZ - hostZ) / max >= 0.15;
    }

    /**
     * Moves a face-hit bounce origin according to {@code interaction}:
     * <ul>
     *   <li>{@link Interaction#CORNER} / {@link Interaction#SPLIT} — lerp toward H's shared
     *       leading vertex (same-sign tangents as {@code rayDir})</li>
     *   <li>{@link Interaction#FACE} — stay on the hit face and lerp along the leading tangent
     *       past H to the vertex shared only by D and E (one cell beyond H's leading edge)</li>
     *   <li>{@link Interaction#GAP} — stay on the occupied face, no walk</li>
     * </ul>
     * The occupied face is the nearer of the cell's two faces on the hit axis (the hit is
     * already on one of them).
     */
    public static @NotNull Vec3d edgeWalk(
            @NotNull Vec3d hit,
            @NotNull Vec3d cellBase,
            int cellSize,
            @NotNull Vec3i face,
            @NotNull Vec3d rayDir,
            double amount,
            @NotNull Interaction interaction
    ) {
        double minX = cellBase.x;
        double minY = cellBase.y;
        double minZ = cellBase.z;
        double maxX = minX + cellSize;
        double maxY = minY + cellSize;
        double maxZ = minZ + cellSize;

        double x = hit.x;
        double y = hit.y;
        double z = hit.z;

        if (face.getX() != 0) {
            x = nearer(hit.x, minX, maxX);
        } else if (face.getY() != 0) {
            y = nearer(hit.y, minY, maxY);
        } else if (face.getZ() != 0) {
            z = nearer(hit.z, minZ, maxZ);
        }

        if (interaction == Interaction.GAP) {
            return new Vec3d(x, y, z);
        }

        double t = Math.max(0.0, Math.min(1.0, amount));
        if (interaction == Interaction.FACE) {
            // Along the wall to the D–E-only vertex: primary tangent past H by one cell.
            int faceAxis = face.getX() != 0 ? 0 : face.getY() != 0 ? 1 : 2;
            int a1 = (faceAxis + 1) % 3;
            int a2 = (faceAxis + 2) % 3;
            double r1 = component(rayDir, a1);
            double r2 = component(rayDir, a2);
            int tangentAxis = Math.abs(r1) >= Math.abs(r2) ? a1 : a2;
            if (tangentAxis == 0) {
                x = walkAxisPast(x, minX, maxX, rayDir.x, cellSize, t);
            } else if (tangentAxis == 1) {
                y = walkAxisPast(y, minY, maxY, rayDir.y, cellSize, t);
            } else {
                z = walkAxisPast(z, minZ, maxZ, rayDir.z, cellSize, t);
            }
            return new Vec3d(x, y, z);
        }

        // CORNER / SPLIT: H's leading vertex.
        if (face.getX() != 0) {
            y = walkAxis(y, minY, maxY, rayDir.y, t);
            z = walkAxis(z, minZ, maxZ, rayDir.z, t);
        } else if (face.getY() != 0) {
            x = walkAxis(x, minX, maxX, rayDir.x, t);
            z = walkAxis(z, minZ, maxZ, rayDir.z, t);
        } else if (face.getZ() != 0) {
            x = walkAxis(x, minX, maxX, rayDir.x, t);
            y = walkAxis(y, minY, maxY, rayDir.y, t);
        }
        return new Vec3d(x, y, z);
    }

    /** Corner walk (legacy default). Prefer the overload that takes {@link Interaction}. */
    public static @NotNull Vec3d edgeWalk(
            @NotNull Vec3d hit,
            @NotNull Vec3d cellBase,
            int cellSize,
            @NotNull Vec3i face,
            @NotNull Vec3d rayDir,
            double amount
    ) {
        return edgeWalk(hit, cellBase, cellSize, face, rayDir, amount, Interaction.CORNER);
    }

    /**
     * Cast plane ({@code -sign(ray)} on the crossed axis) for a hit that already lies on a face of
     * {@code [cellBase, cellBase+cellSize]}. Corner/edge hits prefer the candidate axis with the
     * largest {|ray|} so the known exit matches DDA intent.
     */
    public static @NotNull Vec3i castPlaneAtHit(
            @NotNull Vec3d hit,
            @NotNull Vec3d cellBase,
            int cellSize,
            @NotNull Vec3d rayDir
    ) {
        int s = Math.max(1, cellSize);
        double maxX = cellBase.x + s;
        double maxY = cellBase.y + s;
        double maxZ = cellBase.z + s;
        final double eps = 1e-5;
        int bestAxis = -1;
        double bestAbs = -1.0;
        if (Math.abs(hit.x - cellBase.x) <= eps || Math.abs(hit.x - maxX) <= eps) {
            double a = Math.abs(rayDir.x);
            if (a >= bestAbs) {
                bestAbs = a;
                bestAxis = 0;
            }
        }
        if (Math.abs(hit.y - cellBase.y) <= eps || Math.abs(hit.y - maxY) <= eps) {
            double a = Math.abs(rayDir.y);
            if (a >= bestAbs) {
                bestAbs = a;
                bestAxis = 1;
            }
        }
        if (Math.abs(hit.z - cellBase.z) <= eps || Math.abs(hit.z - maxZ) <= eps) {
            double a = Math.abs(rayDir.z);
            if (a >= bestAbs) {
                bestAxis = 2;
            }
        }
        if (bestAxis < 0) {
            Vec3i face = dominantExitFace(rayDir);
            return new Vec3i(-face.getX(), -face.getY(), -face.getZ());
        }
        double dir = component(rayDir, bestAxis);
        int planeSign = dir > 0 ? -1 : dir < 0 ? 1 : 0;
        if (planeSign == 0) {
            // Hit is on a face but ray is parallel — use which side of the cell the hit sits on.
            double min = bestAxis == 0 ? cellBase.x : bestAxis == 1 ? cellBase.y : cellBase.z;
            double max = min + s;
            double h = component(hit, bestAxis);
            planeSign = Math.abs(h - max) <= eps ? -1 : 1;
        }
        return axisUnit(bestAxis, planeSign);
    }

    /** Unit face along the largest {|component|} of {@code dir} (forward through that face). */
    public static @NotNull Vec3i dominantExitFace(@NotNull Vec3d dir) {
        double ax = Math.abs(dir.x);
        double ay = Math.abs(dir.y);
        double az = Math.abs(dir.z);
        if (ax >= ay && ax >= az) {
            return new Vec3i(dir.x >= 0 ? 1 : -1, 0, 0);
        }
        if (ay >= az) {
            return new Vec3i(0, dir.y >= 0 ? 1 : -1, 0);
        }
        return new Vec3i(0, 0, dir.z >= 0 ? 1 : -1);
    }

    private static double walkAxis(double hit, double min, double max, double ray, double t) {
        if (ray > AXIS_EPS) {
            return lerp(hit, max, t);
        }
        if (ray < -AXIS_EPS) {
            return lerp(hit, min, t);
        }
        return hit;
    }

    /** Leading edge of H, then one more {@code cellSize} into E/D (D–E-only vertex). */
    private static double walkAxisPast(double hit, double min, double max, double ray, int cellSize, double t) {
        if (ray > AXIS_EPS) {
            return lerp(hit, max + cellSize, t);
        }
        if (ray < -AXIS_EPS) {
            return lerp(hit, min - cellSize, t);
        }
        return hit;
    }

    private static double nearer(double v, double a, double b) {
        return Math.abs(v - a) <= Math.abs(v - b) ? a : b;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double component(Vec3d v, int axis) {
        return axis == 0 ? v.x : axis == 1 ? v.y : v.z;
    }

    private static int axisSign(double v) {
        if (v > AXIS_EPS) {
            return 1;
        }
        if (v < -AXIS_EPS) {
            return -1;
        }
        return 0;
    }

    private static Vec3i axisUnit(int axis, int signed) {
        return switch (axis) {
            case 0 -> new Vec3i(signed, 0, 0);
            case 1 -> new Vec3i(0, signed, 0);
            default -> new Vec3i(0, 0, signed);
        };
    }
}
