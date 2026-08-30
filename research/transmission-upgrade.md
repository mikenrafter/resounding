# Thin-partition transmission

## Problem

`Physics.reflection(Z1, Z2)` is a semi-infinite-half-space impedance-mismatch
formula. `Cast.raycast()` applies it independently at every octree-node
boundary a ray crosses. For a 1-block-thick partition (glass, doors, thin
walls) that means a ray pays the full mismatch cost twice — once entering
(air → solid) and once exiting (solid → air) — instead of once for the whole
partition. Since the formula is symmetric, both events are near-total
reflections for any solid with a real impedance mismatch against air, so a
single block of glass ends up close to 100% soundproof: `T_net ≈ (1-R)²`,
which is negligible even when the single-boundary `R` is merely "high" rather
than "near 1".

Confirmed in `run/logs/latest.log` from a `block.block.glass.break` sound
(`debugLogging` on): 210 bounces landed on a branch labeled `air` with
`Zprev` in the tens of millions (a value that only comes from a solid the
ray had just crossed) against a real air `Z≈427`, producing `R≈0.96-1.00`.
Visually this reads as "the ray bounced off nothing," because the second
(exit) reflection point renders past the (often translucent) glass geometry.

## Fix shipped (2026-08-29)

Minimal, surgical version: `Cast` now remembers `enteredFrom` — the
impedance the ray was in immediately before crossing into the branch it's
currently marked `impeded` by. On the next boundary, if the new branch's
impedance is close (within a generous relative tolerance) to `enteredFrom`,
treat it as exiting back into the same medium the ray started from — skip
the reflection event for that boundary (`R=0, T=1`) instead of computing a
fresh mismatch against the medium it's mid-transit through.

**Follow-up same day**: the first cut of this had no distance gate, so it
also fired for a ray that traveled a long way through a *thick* medium and
then hit another surface with similar impedance far away (e.g. two separate
stone walls several blocks apart) — indistinguishable from a real second
surface, but treated as a thin-partition pass-through, i.e. rays "phasing
through" blocks they should have reflected off. Added `enteredThickness`
(the distance traveled through the medium being exited) and only treat it
as a thin partition when that's below `THIN_MEMBRANE_MAX_THICKNESS` (1.5
blocks, covering a diagonal crossing of a single voxel). Logic extracted to
`Cast.isThinMembraneExit()` for direct unit testing.

This only detects the "went in, came right back out into the same kind of
medium" case. It does not model partial reflection off a thin membrane
(which real glass does have, just far less than two chained full-mismatch
events), and it does not address a separate, smaller issue noticed while
diagnosing this: `Cast.impeded` gets updated inside `Cast.raycast()`
unconditionally, before `Engine.raycast()`'s loop decides whether the ray
actually reflects or transmits — so a ray that *reflects* off a boundary and
stays in its original medium still has `impeded` overwritten to the
branch it bounced off of, not the medium it's actually still in. This
happened to not matter for the glass case (impeded was already correct
going into the reflect decision there), but is worth its own look.

## Follow-up options, not implemented

- **Proper thin-film transmission model**: replace the two chained
  half-space reflection events with a real thickness-aware transmission
  coefficient (e.g. mass-law-style, using material thickness from the
  octree node size). More physically correct, touches
  `Physics.java`/`Cast.java` more broadly, and needs a decision on where
  material "thickness" comes from (node size vs. a per-material property).
- **Fix `impeded` update timing**: move the `impeded`/`enteredFrom` write out
  of `Cast.raycast()` and into `Engine.raycast()`'s loop, after the
  reflect/transmit decision is known, so a reflected ray never has its
  tracked medium silently swapped to the medium it bounced off of.
