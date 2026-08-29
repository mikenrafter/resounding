package dev.thedocruby.resounding.material;

import dev.thedocruby.resounding.tag.Ident;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A material definition as authored, normalized and immutable.
 *
 * <p>Every physical field is nullable and that is meaningful: null means "inherit from a lower
 * layer or from my solutes", which is what makes a three-line resource-pack tweak possible.
 *
 * <p>{@code solute} and {@code composition} are normalized to the same length at construction, with
 * missing entries left null (read as 1). The previous code defaulted a null composition array to
 * {@code new Double[0]} and then indexed it against {@code solute.length}, so any material with
 * solutes and no explicit composition — the common case, composition being optional — threw
 * {@code ArrayIndexOutOfBoundsException} on the first iteration.
 *
 * @param weight      relative importance of this material when blended into another
 * @param solvent     the material this one is dissolved in; drives the permeation calculation.
 *                    Null means "no distinct solvent", in which case the bake uses 80% of the
 *                    material's own impedance, preserving the previous behaviour.
 * @param solute      constituents whose properties blend into this material
 * @param composition per-solute blend weight, parallel to {@code solute}
 * @param ratio       average by composition alone rather than by constituent weight
 * @param granularity boundary count between solvent and solute; infinity means fully opaque
 * @param melt        melting point, degrees Celsius
 * @param boil        boiling point, degrees Celsius
 * @param temperature overrides ambient temperature
 * @param density     kg/m³
 * @param swave       shear-wave velocity, used for solids
 * @param lwave       longitudinal-wave velocity, used for fluids
 */
public record RawMaterialDef(
        Double weight,
        Ident solvent,
        List<Ident> solute,
        List<Double> composition,
        Boolean ratio,
        Double granularity,
        Double melt,
        Double boil,
        Double temperature,
        Double density,
        Double swave,
        Double lwave
) {
    /** Normalizes {@code solute}/{@code composition} to equal length and takes immutable copies. */
    public RawMaterialDef {
        if (solute != null) {
            List<Ident> soluteCopy = List.copyOf(solute);
            int n = soluteCopy.size();
            Double[] compArray = new Double[n];
            if (composition != null) {
                int limit = Math.min(composition.size(), n);
                for (int i = 0; i < limit; i++) {
                    compArray[i] = composition.get(i);
                }
            }
            solute = soluteCopy;
            composition = Collections.unmodifiableList(Arrays.asList(compArray));
        } else {
            solute = null;
            composition = null;
        }
    }

    public static RawMaterialDef empty() {
        return new RawMaterialDef(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Layers {@code higher} on top of this definition, per field.
     *
     * <p>A non-null field in {@code higher} wins; a null inherits from this one. Whole-record
     * replacement was rejected because it would force a pack to restate every field to change one,
     * and because referencing the shadowed definition as a solute of its own replacement is a
     * same-name cycle.
     *
     * <p><b>{@code solute} and {@code composition} replace atomically as a pair.</b> Taking one from
     * each layer would desynchronize their lengths, which is precisely the defect the normalizing
     * constructor exists to prevent.
     */
    public RawMaterialDef overlay(RawMaterialDef higher) {
        Double w = higher.weight() != null ? higher.weight() : this.weight();
        Ident s = higher.solvent() != null ? higher.solvent() : this.solvent();
        List<Ident> sol;
        List<Double> comp;
        if (higher.solute() != null) {
            sol = higher.solute();
            comp = higher.composition();
        } else {
            sol = this.solute();
            comp = this.composition();
        }
        Boolean r = higher.ratio() != null ? higher.ratio() : this.ratio();
        Double g = higher.granularity() != null ? higher.granularity() : this.granularity();
        Double m = higher.melt() != null ? higher.melt() : this.melt();
        Double b = higher.boil() != null ? higher.boil() : this.boil();
        Double t = higher.temperature() != null ? higher.temperature() : this.temperature();
        Double d = higher.density() != null ? higher.density() : this.density();
        Double sw = higher.swave() != null ? higher.swave() : this.swave();
        Double lw = higher.lwave() != null ? higher.lwave() : this.lwave();

        return new RawMaterialDef(w, s, sol, comp, r, g, m, b, t, d, sw, lw);
    }

    /** True when every property needed to bake is present. */
    public boolean isComplete() {
        return granularity != null
                && melt != null
                && boil != null
                && temperature != null
                && density != null
                && swave != null
                && lwave != null;
    }
}
