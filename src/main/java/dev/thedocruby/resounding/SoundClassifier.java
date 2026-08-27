package dev.thedocruby.resounding;

import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.util.regex.Pattern;

/**
 * Classifies sounds by name/category to decide routing, filtering, and position adjustments.
 *
 * Sound names are matched against compiled regex patterns to decide:
 *   - Whether to skip tracing entirely (UI, music, spam)
 *   - Whether to apply position offsets (records, footsteps)
 *   - Whether to use gentle reverb parameters (ambient, splash)
 * The colors array provides debug-display colors for raycasting visualization.
 */
public class SoundClassifier {
    private SoundClassifier() {}

    // TODO tagging system
    public static final Pattern spamPattern   = Pattern.compile(".*(rain|lava).*");
    public static final Pattern stepPattern   = Pattern.compile(".*(step|pf_).*");  // includes presence_footsteps
    public static final Pattern gentlePattern = Pattern.compile(".*(ambient|splash|swim|note|compounded).*");
    public static final Pattern ignorePattern = Pattern.compile(".*(music|voice).*");
    public static final Pattern uiPattern     = Pattern.compile("ui\\..*");

    static Integer[] colors = new Integer[] {
            Formatting.GREEN.getColorValue(),
            Formatting.AQUA .getColorValue(), Formatting.LIGHT_PURPLE.getColorValue(), Formatting.DARK_PURPLE.getColorValue(),
            Formatting.RED  .getColorValue(), Formatting.GOLD        .getColorValue(), Formatting.YELLOW     .getColorValue()
    };

    // TODO calculate with atmospherics effect
    // determined by temperature & humidity (global transmission coefficient -> alters permeability)
    public static double transmission = 1;

    /** Adjusts a sound's origin position based on category/tag, or returns null to skip tracing. */
    public static Vec3d adjustSource(SoundCategory category, String tag, Vec3d soundPos) {
        Vec3d offset = new Vec3d(0, 0, 0);
        if (category == SoundCategory.RECORDS) offset = offset.add(0.5, 0.5, 0.5);
        else if (stepPattern.matcher(tag).matches()) offset = offset.add(0.0, 0.2, 0.0);
        return uiPattern.matcher(tag).matches()
                || ignorePattern.matcher(tag).matches()
                || spamPattern.matcher(tag).matches()
                ? null : soundPos.add(offset);
    }
}
