package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.material.Material;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.jetbrains.annotations.NotNull;

/**
 * Phase 2 acoustic patch (frustums-plan.md "Acoustic patch aggregation pass"): one exposed block
 * face, tagged with its outward-facing {@code normal} (always one of the 6 axis-unit directions,
 * same convention as {@link Step#plane()}), world-space {@code centroid}, {@code area} (blocks²),
 * and baked {@code material} (reusing {@code MaterialRegistry}/{@link Material} lookups already
 * used by {@code OctreeManager.growOctree}).
 *
 * <p>Built by {@link PatchAggregator}, independent of {@code OctreeManager.growOctree}'s own
 * material-octree build. Shared substrate for Phase 1 (candidate reflect/terminate surfaces for a
 * beam), Phase 3 ({@link ImageSource} mirror candidates), and Phase 4 (bounce-chain endpoints).
 */
public record Patch(
        @NotNull Vec3d centroid,
        @NotNull Vec3i normal,
        double area,
        @NotNull Material material
) {}
