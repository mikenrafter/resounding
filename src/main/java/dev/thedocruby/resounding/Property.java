package dev.thedocruby.resounding;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.LinkedList;

public class Property {
    boolean ratio;
    LinkedList<Double> values = new LinkedList<>();
    int count = 0;
    double added = 0;
    boolean valid = false;
    public Property(boolean ratio) {
        this.ratio = ratio;
    }

    public @Nullable Double get() {
        if (!valid) return null;
        double sum = values.stream().reduce(0D, Double::sum);
        if (count > 0)
            sum /= count;
        return sum + added;
    }

    /**
     * Adds a value to this property accumulator.
     *
     * Operates in three modes based on count and weight:
     * - Override  (count == 0): Clears all previous values, sets a new base.
     *                           Also updates ratio behavior if ratioUpdate is non-null.
     * - Adjust   (weight == 0, count != 0): Adds an offset to the accumulated total
     *                                       without affecting averaging weight.
     * - Weighted  (default): Adds a weighted value contribution to the accumulation.
     *                        If ratio mode is off, the value is further scaled by weight.
     *
     * @param value       The value to add, or null to skip (returns false)
     * @param weight      The weight coefficient applied when ratio mode is off (0 = adjust mode)
     * @param count       The contribution count for averaging (0 = override mode)
     * @param ratioUpdate When non-null in override mode, switches between ratio/weight averaging
     * @return true if the value was successfully added
     */
    public boolean add(@Nullable Double value, double weight, double count, @Nullable Boolean ratioUpdate) {
        // only update ratio status if override is set
        if (count == 0) this.ratio = ratioUpdate == null ? this.ratio : ratioUpdate;
        if (value == null) return false;
        valid = true;
        double next = value * count;
        // allows overriding values
        if (count == 0) {
            values = new LinkedList<>();
            next = value;
            this.count = 0;
        // allows adjusting values
        } else if (weight == 0) {
            //*/
            added += next;
            /*/ // TODO consider order-dependent adjust
            // [7...] + [5*7] = 5 higher, keep adding = + [10], now a [9...], treated like an [8...]
            // but the 5 gets weighted for the last item -> is this useful? or just confusing?
            values.add(next * this.count);
            //*/
            return true;
        }
        // TODO refactor this?...
        // currently this makes the ratio override process order-dependent.
        // With parenting this isn't an issue, but this is still a suboptimal implementation
        if (!ratio) next *= weight;

        values.add(next);
        this.count += count;
        return true;
    }
}
