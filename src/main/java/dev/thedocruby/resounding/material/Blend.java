package dev.thedocruby.resounding.material;

/**
 * Accumulator that blends one physical property across a material's constituents.
 *
 * <p>Replaces {@code Property}. The three modes are preserved deliberately — they are the design,
 * not the bug:
 * <ul>
 *   <li><b>Override</b> ({@code count == 0}) — discard what came before and set a new base. This is
 *       how a child material inherits from its parent before selectively overriding.
 *   <li><b>Adjust</b> ({@code weight == 0}, {@code count != 0}) — offset the total without
 *       contributing to the averaging weight.
 *   <li><b>Weighted</b> — contribute a weighted share of the average.
 * </ul>
 *
 * <p>The bug was arithmetic: the running count was an {@code int} while {@code add} took a
 * {@code double}, so {@code this.count += count} applied Java's implicit narrowing on compound
 * assignment and silently truncated. A composition of {@code 0.5} counted as zero. Here the
 * accumulator is a {@code double} and fractional compositions survive exactly.
 */
public final class Blend {

    private boolean ratio;
    private double sum;
    private double count;
    private double added;
    private boolean valid;

    /**
     * @param ratio true to average by stated composition alone; false to also scale each
     *              contribution by its constituent's weight
     */
    public Blend(boolean ratio) {
        this.ratio = ratio;
        this.sum = 0.0;
        this.count = 0.0;
        this.added = 0.0;
        this.valid = false;
    }

    /**
     * @param value       the contribution, or null to skip entirely
     * @param weight      scaling applied when not in ratio mode; 0 selects Adjust mode
     * @param count       averaging weight; 0 selects Override mode
     * @param ratioUpdate in Override mode only, switches the averaging strategy; null leaves it
     * @return true if a value was recorded
     */
    public boolean add(Double value, double weight, double count, Boolean ratioUpdate) {
        if (count == 0.0 && ratioUpdate != null) {
            this.ratio = ratioUpdate;
        }
        if (value == null) {
            return false;
        }
        valid = true;
        double next = value * count;
        if (count == 0.0) {
            sum = 0.0;
            added = 0.0;
            this.count = 0.0;
            next = value;
        } else if (weight == 0.0) {
            added += next;
            return true;
        }
        if (!ratio) {
            next *= weight;
        }
        sum += next;
        this.count += count;
        return true;
    }

    /** The blended value, or null if nothing valid was ever added. */
    public Double get() {
        if (!valid) {
            return null;
        }
        double result = sum;
        if (count > 0.0) {
            result /= count;
        }
        return result + added;
    }
}
