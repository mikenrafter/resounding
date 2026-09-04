package dev.thedocruby.resounding.raycast;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Records octant boxes (real LOD nodes or virtual finer steps) a frustum beam would traverse.
 * Uses {@link FrustumLod} step schedule; does not subdivide {@link Branch#children}.
 *
 * <p>Bounce-off neighbors (cells interacted with but not entered) are attached by the debug
 * overlay at reflection kinks, not by this walk.
 */
public final class BeamVisitRecorder {
    private BeamVisitRecorder() {}

    public record VisitedBox(
            @NotNull BlockPos start,
            int size,
            boolean virtual,
            @Nullable Vec3d polar
    ) {
        public VisitedBox(@NotNull BlockPos start, int size, boolean virtual) {
            this(start, size, virtual, null);
        }
    }

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
        Vec3d dir = direction.lengthSquared() > 1e-12 ? direction.normalize() : direction;

        while (distanceSoFar < maxDistance) {
            int requested = FrustumLod.stepForDistance(distanceSoFar, beam.growthRate());
            Branch lod = root.getAtLod(BlockPos.ofFloored(pos), requested);
            int step = Math.min(requested, Math.max(1, lod.size));
            Branch finest = root.get(BlockPos.ofFloored(pos));
            boolean virtual = finest.size > step;

            BlockPos cellOrigin = FrustumLod.alignOrigin(BlockPos.ofFloored(pos), lod.start, step);
            visits.add(new VisitedBox(cellOrigin, step, virtual, lod.polar()));

            pos = pos.add(dir.x * step, dir.y * step, dir.z * step);
            distanceSoFar += step;
        }
        return visits;
    }
}
