package dev.thedocruby.resounding.material;

import dev.thedocruby.resounding.tag.BlockIndex;
import dev.thedocruby.resounding.tag.Diagnostic;
import dev.thedocruby.resounding.tag.Ident;

import java.util.List;
import java.util.Map;

/**
 * Flattens, refines and bakes material definitions into runtime {@link Material}s. Pure.
 */
public final class MaterialResolver {

    /** Global ambient temperature in kelvin, applied unless a material overrides it. */
    public static final double AMBIENT_KELVIN = 287.15D;

    private MaterialResolver() {}

    /** Baked materials plus everything that went wrong producing them. */
    public record Baked(Map<Ident, Material> materials, List<Diagnostic> diagnostics) {}

    /**
     * Generates a skeletal definition per block, listing that block's tags as its solutes.
     *
     * <p>The first tag is the base (composition 0, i.e. Override mode); the rest blend in. Shells
     * carry no physical properties of their own — they exist so a block inherits from whatever its
     * tags describe.
     *
     * <p>Every block in {@code index} gets a shell, including untagged ones, which previously fell
     * out of the pipeline entirely and ended up with no material at all.
     */
    public static Map<Ident, RawMaterialDef> shells(BlockIndex index) {
        throw new UnsupportedOperationException("P4");
    }

    /**
     * Resolves solute references, normalizes temperature, and bakes.
     *
     * <p>Derived properties:
     * <ul>
     *   <li>{@code impedance  = lerp(state, lwave, swave) * density}
     *   <li>{@code permeation = (1 - reflection(impedance, solvent))^(granularity * (1 + state))}
     *   <li>{@code state      = lerpProgress(temperature, melt, boil)}
     * </ul>
     *
     * <p>Definitions still missing required properties after flattening are dropped with a
     * diagnostic rather than baked into nonsense. Cycles and missing solutes are reported, never
     * signalled by null.
     *
     * <p>Purity is part of the contract: {@code definitions} is not mutated. The previous
     * implementation mutated its input map while iterating it.
     */
    public static Baked resolve(Map<Ident, RawMaterialDef> definitions) {
        throw new UnsupportedOperationException("P4");
    }
}
