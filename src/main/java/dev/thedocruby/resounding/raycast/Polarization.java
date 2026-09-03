package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.material.Material;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.TreeSet;

/**
 * Phase 0 / Phase 0.5 shared primitive (frustums-plan.md): the corner-sign-sum polarization of
 * an octant's 8 children, plus the baked high/low/avg impedance descriptor. Pure math over
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
     * share one material, so there is no G_high/G_low split); a real {@code (0,0,0)} polar means
     * a heterogeneous octant whose corner-sign-sum genuinely cancels (e.g. the diagonal
     * checkerboard case) — the two are deliberately distinguishable. {@code primary}/{@code
     * secondary}/{@code blendCoefficient}/{@code s} are likewise {@code null}/{@code NaN} for the
     * no-gradient case.
     */
    public record Descriptor(
            double maxImpedance,
            double minImpedance,
            double avgImpedance,
            @Nullable Vec3d polar,
            @Nullable Material primary,
            @Nullable Material secondary,
            double blendCoefficient,
            double s
    ) {}

    /**
     * Bakes the size-2 (8-child) octant descriptor (frustums-plan.md Phase 0's baked-descriptor
     * table + Phase 0.5's {@code P}/primary/secondary/blend-coefficient/{@code s} derivation) from
     * 8 corner materials in {@code blockSequence} order. Not yet implemented — always throws.
     */
    public static @NotNull Descriptor bakeOctant(@NotNull Material[] corners) {
        if (corners.length != 8) {
            throw new IllegalArgumentException("bakeOctant requires exactly 8 corners in blockSequence order");
        }

        double[] impedances = new double[8];
        double sum = 0.0;
        double max = Double.NEGATIVE_INFINITY;
        double min = Double.POSITIVE_INFINITY;
        TreeSet<Double> distinct = new TreeSet<>();
        for (int i = 0; i < 8; i++) {
            double v = corners[i].impedance();
            impedances[i] = v;
            sum += v;
            if (v > max) max = v;
            if (v < min) min = v;
            distinct.add(v);
        }
        double avg = sum / 8.0;

        if (distinct.size() == 1) {
            // single-material octant: no gradient, distinct from a real (0,0,0) cancellation.
            return new Descriptor(max, min, avg, null, null, null, Double.NaN, Double.NaN);
        }

        double highImpedance;
        double lowImpedance;
        if (distinct.size() >= 4) {
            double[] sorted = impedances.clone();
            java.util.Arrays.sort(sorted);
            double bottomSum = 0.0;
            double topSum = 0.0;
            for (int i = 0; i < 4; i++) bottomSum += sorted[i];
            for (int i = 4; i < 8; i++) topSum += sorted[i];
            highImpedance = topSum / 4.0;
            lowImpedance = bottomSum / 4.0;
        } else {
            highImpedance = max;
            lowImpedance = min;
        }

        // G_high/G_low split: corners at the raw impedance extremes, middle-tier corners
        // (strictly between min and max) excluded, same as Phase 0's aggregate.
        Vec3d highSum = Vec3d.ZERO;
        Vec3d lowSum = Vec3d.ZERO;
        int highCount = 0;
        int lowCount = 0;
        Material primary = null;
        Material secondary = null;
        for (int i = 0; i < 8; i++) {
            double v = impedances[i];
            if (v == max) {
                highSum = highSum.add(cornerSign(CORNER_OFFSETS[i]));
                highCount++;
                if (primary == null) primary = corners[i];
            } else if (v == min) {
                lowSum = lowSum.add(cornerSign(CORNER_OFFSETS[i]));
                lowCount++;
                if (secondary == null) secondary = corners[i];
            }
        }

        Vec3d polar = highSum.subtract(lowSum);
        double blendCoefficient = (double) highCount / (highCount + lowCount);
        double s = magnitudeTerm(highCount);

        return new Descriptor(highImpedance, lowImpedance, avg, polar, primary, secondary, blendCoefficient, s);
    }

    /**
     * Magnitude/gain term {@code s} (frustums-plan.md "Motif-based magnitude boost" / resolved
     * count-based rule): {@code 1} by default, {@code 2} when the primary ({@code G_high})
     * material's count is &ge;6 of the octant's 8 children.
     */
    public static double magnitudeTerm(int primaryCount) {
        return primaryCount >= 6 ? 2.0 : 1.0;
    }

    /**
     * {@code stiff_weight(child) ∈ [0,1]} (frustums-plan.md size&gt;2 combination rule): how
     * stiff/high-impedance-dominant a child octant is, quantized to quarters
     * (25%/50%/75%/100%).
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
