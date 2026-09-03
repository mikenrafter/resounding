package dev.thedocruby.resounding.raycast;

import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3 first-order exact image-source echo search (frustums-plan.md "Phase 3 — First-order
 * echo: exact, not sampled"): a direct geometric query — mirror the source across a candidate
 * {@link Patch}, then test source(mirror)↔listener line-of-sight through the mirror point — seeded
 * by Phase 2's patch list ({@link PatchAggregator}) rather than by beam hits. Mirrors {@code Cast}'s
 * relationship to {@link Hit}, per the plan's own suggested naming/home for this search.
 *
 * <p><b>Capped at order 1</b> (frustums-plan.md: "Cap exact image-source at order 1, order 2 only
 * if profiling shows headroom... Do not extend the exact search further"). This class intentionally
 * offers no order-2+ API.
 *
 * <p>{@link #hasLineOfSight} and {@link #firstOrderEchoes} take an {@link Occluder} rather than
 * doing octree traversal themselves, so the geometric decision (mirror math, candidate selection)
 * stays testable as a pure/near-pure unit decoupled from full {@code World}/{@code ChunkChain}
 * traversal machinery — a design choice beyond the plan's literal text, flagged for review.
 */
public final class ImageSource {
    private ImageSource() {}

    /**
     * One candidate first-order echo: the {@link Patch} it reflects off, and the source position
     * mirrored across that patch's plane (the point a valid echo path must appear to originate
     * from, once visibility is confirmed).
     */
    public record Candidate(@NotNull Patch patch, @NotNull Vec3d mirrorSource) {}

    /**
     * Delegates occlusion testing for the segment {@code from -> to} to whatever geometric
     * traversal the caller wires in (real octree raycast in production, a trivial stub in tests).
     */
    @FunctionalInterface
    public interface Occluder {
        /** True when something blocks the straight segment from {@code from} to {@code to}. */
        boolean isBlocked(@NotNull Vec3d from, @NotNull Vec3d to);
    }

    /**
     * Reflects {@code source} across the infinite plane containing {@code patch} (its
     * {@link Patch#centroid()} and {@link Patch#normal()}) — frustums-plan.md Phase 3's "mirror
     * source across a candidate patch".
     */
    public static @NotNull Vec3d mirrorSource(@NotNull Vec3d source, @NotNull Patch patch) {
        Vec3d normal = Vec3d.of(patch.normal());
        double distance = patch.centroid().subtract(source).dotProduct(normal);
        return source.add(normal.multiply(2.0 * distance));
    }

    /**
     * Source(mirror)↔listener line-of-sight test through the mirror point (frustums-plan.md Phase
     * 3's "line-of-sight test source↔listener through the mirror point"), delegated to
     * {@code occluder} for the actual occlusion query.
     */
    public static boolean hasLineOfSight(@NotNull Vec3d mirrorSource, @NotNull Vec3d listener, @NotNull Occluder occluder) {
        return !occluder.isBlocked(mirrorSource, listener);
    }

    /**
     * Exact, unconditional order-1 image-source search (frustums-plan.md Phase 3: "Keep order-1
     * exact and unconditional"): for every candidate patch, mirrors {@code source} across it and
     * keeps only the patches whose mirror point has clear line-of-sight to {@code listener}. The
     * patch list narrows "test every patch in the world" down to "test only patches near the
     * source/listener" (frustums-plan.md Phase 2/3), but is not filtered further here — that
     * narrowing is the caller's/Phase 2's job.
     *
     * <p>Also rejects geometry that cannot form a first-order specular hit: source on the back
     * side of the patch normal, or a reflection point that falls outside the 1×1 face
     * ({@code centroid ± 0.5} on the two tangent axes).
     */
    public static @NotNull List<Candidate> firstOrderEchoes(
            @NotNull Vec3d source,
            @NotNull Vec3d listener,
            @NotNull List<Patch> patches,
            @NotNull Occluder occluder
    ) {
        List<Candidate> echoes = new ArrayList<>();
        for (Patch patch : patches) {
            if (!isSourceInFront(source, patch)) {
                continue;
            }
            Vec3d mirror = mirrorSource(source, patch);
            Vec3d reflection = reflectionPointOnPlane(mirror, listener, patch);
            if (reflection == null || !isReflectionInFaceBounds(reflection, patch)) {
                continue;
            }
            if (hasLineOfSight(mirror, listener, occluder)) {
                echoes.add(new Candidate(patch, mirror));
            }
        }
        return echoes;
    }

    /** True when {@code source} lies on the outward/front side of {@code patch}'s plane. */
    private static boolean isSourceInFront(@NotNull Vec3d source, @NotNull Patch patch) {
        Vec3d normal = Vec3d.of(patch.normal());
        return source.subtract(patch.centroid()).dotProduct(normal) > 0.0;
    }

    /**
     * Intersection of the mirror→listener segment with the patch plane, or {@code null} when the
     * segment does not cross the plane (parallel / degenerate).
     */
    private static @Nullable Vec3d reflectionPointOnPlane(
            @NotNull Vec3d mirror,
            @NotNull Vec3d listener,
            @NotNull Patch patch
    ) {
        Vec3d normal = Vec3d.of(patch.normal());
        Vec3d toListener = listener.subtract(mirror);
        double denom = toListener.dotProduct(normal);
        if (Math.abs(denom) < 1e-12) {
            return null;
        }
        double t = patch.centroid().subtract(mirror).dotProduct(normal) / denom;
        if (t < 0.0 || t > 1.0) {
            return null;
        }
        return mirror.add(toListener.multiply(t));
    }

    /** 1×1 face extents: {@code centroid ± 0.5} on each axis orthogonal to the face normal. */
    private static boolean isReflectionInFaceBounds(@NotNull Vec3d reflection, @NotNull Patch patch) {
        Vec3i n = patch.normal();
        Vec3d c = patch.centroid();
        final double half = 0.5;
        if (n.getX() != 0) {
            return Math.abs(reflection.y - c.y) <= half && Math.abs(reflection.z - c.z) <= half;
        }
        if (n.getY() != 0) {
            return Math.abs(reflection.x - c.x) <= half && Math.abs(reflection.z - c.z) <= half;
        }
        return Math.abs(reflection.x - c.x) <= half && Math.abs(reflection.y - c.y) <= half;
    }
}
