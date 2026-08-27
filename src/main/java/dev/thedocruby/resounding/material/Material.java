package dev.thedocruby.resounding.material;

import org.jetbrains.annotations.NotNull;

/**
 * Baked acoustic properties of a block, as consumed by the raycaster.
 *
 * <p>Lives beside the resolver rather than in the root package so that the whole material pipeline
 * compiles and runs without Minecraft on the classpath.
 */
public record Material(
        @NotNull Double impedance,   // impedance of material
        @NotNull Double permeation,  // permeation of material (inverse of absorption)
        @NotNull Double state        // state of matter [0 absent, .25 plasma, .5 gas, .75 liquid, 1 solid]
) {
}
