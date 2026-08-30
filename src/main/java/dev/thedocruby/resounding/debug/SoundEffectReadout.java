package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.toolbox.SlotProfile;
import dev.thedocruby.resounding.toolbox.SoundProfile;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.sound.SoundCategory;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Holds the most recently applied sound-effect parameters for on-screen debug display.
 */
@Environment(EnvType.CLIENT)
public final class SoundEffectReadout {

	public record Snapshot(
			String tag,
			SoundCategory category,
			int sourceId,
			SoundProfile profile,
			SlotProfile reverbSlot,
			double directPermeation,
			boolean reverbEnabled,
			boolean occlusionEnabled
	) {}

	private static volatile Snapshot latest;
	private static volatile int version;

	private SoundEffectReadout() {}

	public static void publish(
			String tag,
			SoundCategory category,
			SoundProfile profile,
			SlotProfile reverbSlot,
			double directPermeation,
			boolean reverbEnabled,
			boolean occlusionEnabled
	) {
		latest = new Snapshot(
				tag,
				category,
				profile.sourceID(),
				profile,
				reverbSlot,
				directPermeation,
				reverbEnabled,
				occlusionEnabled
		);
		version++;
	}

	public static @Nullable Snapshot latest() {
		return latest;
	}

	public static int version() {
		return version;
	}

	public static String format(@Nullable Snapshot snapshot) {
		if (snapshot == null) {
			return "";
		}
		SoundProfile profile = snapshot.profile();
		SlotProfile slot = snapshot.reverbSlot();
		StringBuilder builder = new StringBuilder(160);
		builder.append(String.format(
				Locale.ROOT,
				"%s.%s id=%d",
				snapshot.category().getName(),
				snapshot.tag(),
				snapshot.sourceId()
		));
		if (snapshot.occlusionEnabled()) {
			builder.append(String.format(
					Locale.ROOT,
					" occ=%.0f%%",
					snapshot.directPermeation() * 100.0
			));
		}
		builder.append(String.format(
				Locale.ROOT,
				" direct=%.2f/%.2f",
				profile.directGain(),
				profile.directCutoff()
		));
		if (snapshot.reverbEnabled()) {
			builder.append(String.format(
					Locale.ROOT,
					" rvb s%d=%.2f/%.2f",
					slot.slot(),
					slot.gain(),
					slot.cutoff()
			));
		}
		return builder.toString();
	}

	static void clear() {
		latest = null;
		version++;
	}
}
