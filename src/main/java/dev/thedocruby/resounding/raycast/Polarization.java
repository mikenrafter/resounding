package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.material.Material;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Phase 0 / Phase 0.5 shared primitive (frustums-plan.md): the corner-sign-sum polarization of
 * an octant's 8 children, plus the baked most/least-common impedance descriptor. Pure math over
 * {@link Material} data only — no {@link Branch}/tree access, so it can be exercised directly by
 * both {@code OctreeManager.growOctree}'s bake pass (Phase 0) and the native-resolution
 * reflection/permeation path (Phase 0.5).
 *
 * <p>All 8-corner inputs are expected in {@code OctreeManager.blockSequence} order (idx 0-7:
 * {@code (0,0,0) (1,0,0) (0,1,0) (1,1,0) (0,0,1) (1,0,1) (0,1,1) (1,1,1)}).
 */
public final class Polarization {
    private Polarization() {}

    /**
     * Corner offsets in {@code OctreeManager.blockSequence} order, duplicated here (rather than
     * imported) to keep this class pure math with no {@code OctreeManager}/{@code Branch} coupling.
     */
    private static final BlockPos[] CORNER_OFFSETS = {
            new BlockPos(0, 0, 0), new BlockPos(1, 0, 0),
            new BlockPos(0, 1, 0), new BlockPos(1, 1, 0),
            new BlockPos(0, 0, 1), new BlockPos(1, 0, 1),
            new BlockPos(0, 1, 1), new BlockPos(1, 1, 1),
    };

    /**
     * Per-corner signed direction vector {@code d_c = (2*offsetX-1, 2*offsetY-1, 2*offsetZ-1)},
     * {@code offset} being one of the 0/1-component entries of {@code OctreeManager.blockSequence}.
     */
    public static @NotNull Vec3d cornerSign(@NotNull BlockPos offset) {
        return new Vec3d(
                2 * offset.getX() - 1,
                2 * offset.getY() - 1,
                2 * offset.getZ() - 1
        );
    }

    /**
     * Baked descriptor for one octant. {@code polar == null} means "no gradient" (all 8 corners
     * share one impedance); a real {@code (0,0,0)} polar means a heterogeneous octant whose
     * corner-sign-sum genuinely cancels — the two are deliberately distinguishable.
     *
     * <p>{@code mostCommonImpedance}/{@code leastCommonImpedance} store {@code g_most}/{@code
     * g_least} — presence-split endpoints (not raw max/min impedance). Alignment lerps from
     * most-common (primary, {@code w=1}) toward least-common (secondary, {@code w=0}).
     */
    public record Descriptor(
            double mostCommonImpedance,
            double leastCommonImpedance,
            double avgImpedance,
            @Nullable Vec3d polar,
            @Nullable Material primary,
            @Nullable Material secondary,
            double blendCoefficient,
            double s
    ) {}

    /**
     * Presence-split impedance adjuster:
     * {@code f(mostly_mean, other_mean, overall_ratio) = other_mean * (1 - overall_ratio²)
     * + mostly_mean * √overall_ratio}.
     */
    public static double groupAdjust(double mostlyMean, double otherMean, double overallRatio) {
        return otherMean * (1.0 - overallRatio * overallRatio)
                + mostlyMean * Math.sqrt(overallRatio);
    }

    /**
     * Bakes the size-2 (8-child) octant descriptor from 8 corner materials in {@code blockSequence}
     * order.
     * <ul>
     *   <li>Polar: every corner joins the top or bottom impedance half (middle tier included).</li>
     *   <li>Primary/secondary: most-common / least-common materials (presence ties → stiffer
     *       primary, softer secondary).</li>
     *   <li>{@code g_most}/{@code g_least}: {@link #groupAdjust} over those material groups'
     *       means and count ratio — presence endpoints, not impedance extremes.</li>
     * </ul>
     */
    public static @NotNull Descriptor bakeOctant(@NotNull Material[] corners) {
        if (corners.length != 8) {
            throw new IllegalArgumentException("bakeOctant requires exactly 8 corners in blockSequence order");
        }

        double[] impedances = new double[8];
        double sum = 0.0;
        double max = Double.NEGATIVE_INFINITY;
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            double v = corners[i].impedance();
            impedances[i] = v;
            sum += v;
            if (v > max) max = v;
            if (v < min) min = v;
        }
        double avg = sum / 8.0;

        if (max == min) {
            return new Descriptor(max, min, avg, null, null, null, Double.NaN, Double.NaN);
        }

        // Polar membership: impedance-ranked top/bottom halves (all 8 corners, no middle drop).
        Integer[] order = {0, 1, 2, 3, 4, 5, 6, 7};
        Arrays.sort(order, Comparator
                .comparingDouble((Integer i) -> impedances[i])
                .thenComparingInt(i -> i));
        boolean[] inHigh = new boolean[8];
        for (int r = 4; r < 8; r++) {
            inHigh[order[r]] = true;
        }

        Vec3d highDir = Vec3d.ZERO;
        Vec3d lowDir = Vec3d.ZERO;
        for (int i = 0; i < 8; i++) {
            Vec3d sign = cornerSign(CORNER_OFFSETS[i]);
            if (inHigh[i]) {
                highDir = highDir.add(sign);
            } else {
                lowDir = lowDir.add(sign);
            }
        }
        Vec3d polar = highDir.subtract(lowDir);

        Material primary = selectMostCommon(corners);
        Material secondary = selectLeastCommon(corners, primary);

        double mostSum = 0.0;
        double leastSum = 0.0;
        int mostCount = 0;
        int leastCount = 0;
        for (int i = 0; i < 8; i++) {
            if (corners[i].equals(primary)) {
                mostSum += impedances[i];
                mostCount++;
            } else if (corners[i].equals(secondary)) {
                leastSum += impedances[i];
                leastCount++;
            }
        }
        if (mostCount == 0 || leastCount == 0) {
            throw new IllegalStateException("heterogeneous octant must have both most- and least-common corners");
        }

        double meanMost = mostSum / mostCount;
        double meanLeast = leastSum / leastCount;
        double gMost = groupAdjust(meanMost, meanLeast, (double) mostCount / leastCount);
        double gLeast = groupAdjust(meanLeast, meanMost, (double) leastCount / mostCount);

        double blendCoefficient = mostCount / 8.0;
        double s = magnitudeTerm(mostCount);

        return new Descriptor(gMost, gLeast, avg, polar, primary, secondary, blendCoefficient, s);
    }

    /** Most-common material; presence ties broken toward the stiffer (higher-impedance) material. */
    private static @NotNull Material selectMostCommon(@NotNull Material[] corners) {
        Map<Material, Integer> counts = countMaterials(corners);
        Material best = corners[0];
        int bestCount = -1;
        for (Map.Entry<Material, Integer> e : counts.entrySet()) {
            Material m = e.getKey();
            int c = e.getValue();
            if (c > bestCount
                    || (c == bestCount && m.impedance() > best.impedance())) {
                best = m;
                bestCount = c;
            }
        }
        return best;
    }

    /**
     * Least-common material among those that are not primary; presence ties broken toward the
     * softer (lower-impedance) material.
     */
    private static @NotNull Material selectLeastCommon(@NotNull Material[] corners, @NotNull Material primary) {
        Map<Material, Integer> counts = countMaterials(corners);
        Material best = null;
        int bestCount = Integer.MAX_VALUE;
        for (Map.Entry<Material, Integer> e : counts.entrySet()) {
            Material m = e.getKey();
            if (m.equals(primary)) {
                continue;
            }
            int c = e.getValue();
            if (best == null
                    || c < bestCount
                    || (c == bestCount && m.impedance() < best.impedance())) {
                best = m;
                bestCount = c;
            }
        }
        if (best == null) {
            throw new IllegalStateException("heterogeneous octant must have a least-common material");
        }
        return best;
    }

    private static @NotNull Map<Material, Integer> countMaterials(@NotNull Material[] corners) {
        Map<Material, Integer> counts = new HashMap<>();
        for (Material m : corners) {
            counts.merge(m, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Magnitude/gain term {@code s}: {@code 1} by default, {@code 2} when the primary (most-common)
     * material's count is &ge;6 of the octant's 8 children.
     */
    public static double magnitudeTerm(int primaryCount) {
        return primaryCount >= 6 ? 2.0 : 1.0;
    }

    /**
     * {@code stiff_weight(child) ∈ [0,1]} (frustums-plan.md size&gt;2 combination rule): how
     * presence-dominant a child octant is, quantized to quarters (25%/50%/75%/100%).
     */
    public static double stiffWeight(double blendCoefficient) {
        return Math.round(blendCoefficient * 4.0) / 4.0;
    }

    /**
     * {@code combined = Σ(child.polar × stiff_weight(child)) / Σ(stiff_weight(child))},
     * component-wise, <b>not</b> renormalized to unit length afterward (frustums-plan.md size&gt;2
     * combination rule).
     */
    public static @NotNull Vec3d combinePolar(@NotNull Vec3d[] childPolar, double[] childStiffWeight) {
        double sumWeight = 0.0;
        double x = 0.0, y = 0.0, z = 0.0;
        for (int i = 0; i < childPolar.length; i++) {
            double w = childStiffWeight[i];
            Vec3d p = childPolar[i];
            x += p.x * w;
            y += p.y * w;
            z += p.z * w;
            sumWeight += w;
        }
        if (sumWeight == 0.0) {
            return Vec3d.ZERO;
        }
        return new Vec3d(x / sumWeight, y / sumWeight, z / sumWeight);
    }
}
