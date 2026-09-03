package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.material.Acoustics;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.jetbrains.annotations.NotNull;

/**
 * LOD frustum growth, step schedule, and the 2D forward-orthant interaction map used at a face
 * hit (XY / XZ / YZ).
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
 * <p>At a hit, the ray occupies {@code H} (open). The three cells in the forward orthant on the
 * hit-face plane are {@code N} (through the hit face), {@code E} (along the leading tangent), and
 * {@code D} ({@code N+E}). Trailing cells are never surveyed. Four maps (same heading):
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
     * Same-size neighbor offsets from {@code H}: {@code N} through the hit face, {@code E} along
     * the leading tangent of the hit-face plane, {@code D = N+E}.
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
     * interaction: {@code lerp(min: leftoverEnergy, max: 1, t: polarAlignment) * previousSize *
     * (1 + growthPerBlock * stepDistance)}.
     *
     * <p>{@code polarAlignment} is the raw {@code (rayNorm·polNorm)²} alignment at the boundary just
     * resolved (1.0 when the boundary had no polarization vector — a clean, unambiguous interface
     * imposes no throttle). {@code leftoverEnergyCoefficient} is whichever of reflectivity/
     * transmission actually carried the beam forward through that boundary (only one applies per
     * step: the beam either reflected or transmitted). Full alignment lets the frustum grow at its
     * nominal geometric rate regardless of energy loss; poor alignment (diffuse/grazing) throttles
     * toward the fraction of energy that survived (shrink still applies when {@code growthPerBlock}
     * is 0 — {@code (1 + 0 * distance)} is a no-op).
     *
     * <p>Callers should pass {@link #gatedGrowthPerBlock} so growth only arms after the second
     * permeation at the current floored LOD step since the last bounce.
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
        double blend = leftoverEnergyCoefficient + (1.0 - leftoverEnergyCoefficient) * polarAlignment;
        return blend * previousSize * (1.0 + growthPerBlock * stepDistance);
    }

    /**
     * Growth is armed only after the second permeation at the current floored LOD step since the
     * last bounce. Until then (and on every bounce), return 0 so
     * {@code (1 + growth * distance)} is a no-op while energy/alignment shrink still applies.
     */
    public static double gatedGrowthPerBlock(double growthPerBlock, int permeatesAtSizeSinceBounce) {
        return permeatesAtSizeSinceBounce >= 2 ? growthPerBlock : 0.0;
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
        int axis = face.getX() != 0 ? 0 : face.getY() != 0 ? 1 : 2;
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
     * Forward orthant on the hit-face plane: {@code N} through the face, {@code E} along the
     * leading tangent (the other face-plane axis with larger {|ray|}; ties prefer the cyclic
     * next axis), {@code D = N+E}. A head-on ray (no tangent) yields a zero {@code E} offset.
     */
    public static @NotNull ForwardMap forwardMap(@NotNull Vec3i face, @NotNull Vec3d rayDir, int cellSize) {
        int s = Math.max(1, cellSize);
        int faceAxis = face.getX() != 0 ? 0 : face.getY() != 0 ? 1 : 2;
        int a1 = (faceAxis + 1) % 3;
        int a2 = (faceAxis + 2) % 3;
        double r1 = component(rayDir, a1);
        double r2 = component(rayDir, a2);
        int tangentAxis = Math.abs(r1) >= Math.abs(r2) ? a1 : a2;
        int nSign = axisSign(component(rayDir, faceAxis));
        if (nSign == 0) {
            int f = faceAxis == 0 ? face.getX() : faceAxis == 1 ? face.getY() : face.getZ();
            nSign = -Integer.signum(f);
            if (nSign == 0) {
                nSign = 1;
            }
        }
        int eSign = axisSign(component(rayDir, tangentAxis));
        Vec3i n = axisUnit(faceAxis, nSign * s);
        Vec3i e = axisUnit(tangentAxis, eSign * s);
        Vec3i d = new Vec3i(n.getX() + e.getX(), n.getY() + e.getY(), n.getZ() + e.getZ());
        return new ForwardMap(n, e, d, faceAxis, tangentAxis);
    }

    /**
     * Four-map classifier. {@code n}/{@code e}/{@code d} are whether the host→neighbor interaction
     * blocks (low permeate — see {@link #blocksPermeation}). Head-on (no {@code E}) is
     * {@link Interaction#FACE} when {@code N} blocks, else {@link Interaction#GAP}.
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
        if (nBlocks) {
            return Interaction.GAP;
        }
        if (eBlocks && dBlocks) {
            return Interaction.FACE;
        }
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
