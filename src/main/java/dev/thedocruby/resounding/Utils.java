package dev.thedocruby.resounding;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Odds and ends shared across the mod.
 *
 * <p>This used to carry the tagging pipeline's plumbing — a memoizer that mutated its input map, a
 * null-unsafe filter, a Gson type token that captured a bare type variable and only worked by
 * accident, and a config reader that leaked its file handle. All of that moved into
 * {@code tag/}, {@code material/} and {@code data/}, where it is covered by tests that run without
 * Minecraft, and the versions here were deleted rather than left as a second implementation.
 */
// Not final, and no private constructor: Context and Effect extend this to inherit
// LOGGER and EffectParameter. That is not a pattern worth defending, but untangling it is
// unrelated to the tagging work and would touch the OpenAL layer.
public class Utils {

    public static final Logger LOGGER = LogManager.getLogger("Resounding");

    /** Tuple binding a human-readable name, an OpenAL enum constant, and a value for one effect parameter. */
    public record EffectParameter(
            String name,   // human-readable label (e.g. "density")
            int alEnum,    // OpenAL EXTEfx parameter constant
            float value    // parameter value to apply
    ) {}

    public static <T> double logBase(T x, T b) {
        return Math.log((Double) x) / Math.log((Double) b);
    }
}
