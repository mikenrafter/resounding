package dev.thedocruby.resounding.openal;

/**
 * Holds per-effect OpenAL object IDs (slots, effects, filters).
 *
 * Each Effect instance owns one ALset, so these are instance fields —
 * not static — to prevent separate effects from overwriting each other's state.
 */
public class ALset {
	public ALset() {
		slots   = new int[0];
		effects = new int[0];
		filters = new int[0];
	}
	// AL objects
	public long  old = -1; // prior context id for save/restore
	public long  self    ; // owning context id
	public int   direct  ; // direct low-pass filter
	public int[] slots   ; // auxiliary effect slots
	public int[] effects ; // effect objects
	public int[] filters ; // send filters
}

