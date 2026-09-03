package dev.thedocruby.resounding.raycast;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase C beam-cast visit capture: records octant boxes (real leaves or virtual finer steps) a
 * {@link Beam} would traverse through a known {@link Branch} tree. Used by debug overlays and
 * unit tests without a full Minecraft client.
 */
public final class BeamVisitRecorder {
    private BeamVisitRecorder() {}

    public record VisitedBox(@NotNull BlockPos start, int size, boolean virtual) {}

    /**
     * Walks {@code root} along {@code beam} from {@code origin} in {@code direction} up to
     * {@code maxDistance}, recording each step box at {@link Cast#effectiveStepSize(int, double)}
     * resolution. Does not subdivide {@link Branch#leaves}; finer steps are marked virtual.
     */
    public static @NotNull List<VisitedBox> collectAlongBeam(
            @NotNull Branch root,
            @NotNull Beam beam,
            @NotNull Vec3d origin,
            @NotNull Vec3d direction,
            double maxDistance
    ) {
        List<VisitedBox> visits = new ArrayList<>();
        Vec3d pos = origin;
        double distanceSoFar = 0.0;

        while (distanceSoFar < maxDistance) {
            Branch leaf = root.get(BlockPos.ofFloored(pos));
            int step = Cast.effectiveStepSize(leaf.size, beam.footprintRadiusAt(distanceSoFar));
            if (step <= 0) break;

            int sx = align(pos.x, leaf.start.getX(), step);
            int sy = align(pos.y, leaf.start.getY(), step);
            int sz = align(pos.z, leaf.start.getZ(), step);
            visits.add(new VisitedBox(new BlockPos(sx, sy, sz), step, step < leaf.size));

            pos = pos.add(direction.x * step, direction.y * step, direction.z * step);
            distanceSoFar += step;
        }
        return visits;
    }

    /** Floor {@code coord} onto the step grid that tiles {@code leafStart}. */
    private static int align(double coord, int leafStart, int step) {
        return leafStart + (int) Math.floor((coord - leafStart) / (double) step) * step;
    }
}
