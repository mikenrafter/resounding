package dev.thedocruby.resounding.data;

import java.util.List;

/**
 * Collapses ordered layers into one set of definitions.
 *
 * <p>Order, lowest to highest: generated shells, then the mod's own defaults, then enabled resource
 * packs in application order. <b>A generated shell never replaces an authored material.</b> That
 * precedence rule is the fix for the previous behaviour, where shell generation ran
 * {@code putAll} last and silently overwrote every colliding authored material with an empty
 * skeleton.
 *
 * <p>Merge semantics differ by kind, deliberately:
 * <ul>
 *   <li><b>Tags</b> union across layers, with an opt-in {@code "replace": true} to discard lower
 *       layers — vanilla datapack semantics, which pack authors already know.
 *   <li><b>Materials</b> overlay per field, so a pack retunes one property without restating the
 *       rest. {@code solute} and {@code composition} move together as a pair.
 * </ul>
 */
public final class LayeredSource {

    private LayeredSource() {}

    /**
     * @param lowestFirst layers in ascending precedence
     * @return a single layer named for the merge, carrying the union of all diagnostics
     */
    public static Layer merge(List<Layer> lowestFirst) {
        throw new UnsupportedOperationException("P5");
    }
}
