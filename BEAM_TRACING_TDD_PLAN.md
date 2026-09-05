# Beam-tracing frustum fixes (A–E) — orchestration handoff

Branch: `beam-tracing-frustums`. This doc is a full context restore for a fresh
orchestrating agent after a session reboot — a prior long session did all the
design/consultation work; only implementation remains for D–E.

**Directive from the user, binding for the rest of this work:**
- No more consulting fable. All five designs below are final and approved.
- Launch implementation/red-pass subagents at **medium** thinking effort, not
  high — the designs are precise enough, and the codebase has enough existing
  test/assertion instrumentation, that medium is sufficient.
- Follow strict TDD per task, **sequentially, not in parallel** (C and E both
  add methods to `Physics.java`; running them concurrently risks stepping on
  each other): confirm clean baseline → sonnet red-pass agent (writes failing
  tests only, no production code changes) → verify tests fail for the right
  reason → sonnet implementation agent (given the failing tests + this doc's
  design, makes them pass, no scope creep) → verify full suite green → commit
  → move to next task.
- Build/test command: `nix develop -c ./gradlew test` (or `compileJava` for a
  quick sanity check). Do **not** hand-pick a JDK from `/nix/store` — the
  flake's `nix develop` shell sets `JAVA_HOME` correctly
  (`Resounding dev shell — JAVA_HOME=...`). Plain `./gradlew` outside the
  flake shell fails with "JAVA_HOME is not set".

## Tooling notes (use these; they were useful in the gather phase)

- `agentgrep grep "<exact symbol>" --type java` — exact/regex lexical search,
  groups hits by file with structural context (method/class sections). Faster
  than raw grep for "where is X used" across the whole raycast package.
- `agentgrep find <terms> --type java --max-files N` — ranked file discovery
  when the filename isn't known.
- `agentgrep outline <file>` — quick method/field map before reading a large
  file in full (works well on `Cast.java`, `FrustumLod.java`; less useful on
  files agentgrep can't structurally parse — falls back fine to plain `Read`).
- `agentgrep trace subject:<x> relation:<y>` — relation-aware search, useful
  for "where is this concept produced vs. consumed" questions.
- `rtk read <file>` / `rtk grep <query>` / `rtk rg <query>` — token-compact
  variants; useful when a file is large and a plain `Read` would be wasteful.
  Note: `rtk rg` errored on single-file `--path` targets in this sandbox in
  one prior run — prefer directory-scoped invocations.
- `git log --oneline -- <file>` — useful for finding which recent commit
  introduced a given behavior (this branch has been under heavy iteration;
  `git log --oneline -15` at session start showed the recent commit history).

## Status snapshot (commits so far, newest last)

```
12a929e Task C: dual-derived polarity alignment from incident point and octant center.  [C — DONE]
ead7876 Fix seven pre-existing test failures: Chebyshev radius and Cast pConfig harness.
d072867 Update orchestration plan: mark Task B done, log MaterialRegistry test-pollution flakiness found during verification.
51ec568 Task B: strip polarity from genuine 1x1x1 leaves in virtualLodCell and getBlock.  [B — DONE]
6300ac9 Red pass: pin the polarized-1³-leaf leak (task B) with 3 failing tests.
6c7e2eb Start frustumSize at the growth rate instead of the base footprint.   [A — DONE]
7b48412 Fix vacuum/blank telemetry, group-adjust clamp, and remove thin-membrane special case.  [pre-existing WIP, committed as baseline]
```

- **A** — done, committed (`6c7e2eb`). `Cast.frustumSize` now initializes to
  `pConfig.frustumGrowthPerBlock` instead of `FrustumLod.BASE_FOOTPRINT`.
- **B** — done, committed (`51ec568`). `Branch.virtualLodCell` bakes a
  single-material descriptor for `lodSize == 1` instead of copying the coarse
  descriptor verbatim; `Cast.getBlock` no longer short-circuits on
  `branch.size > 1`, always resolving through `liveLeaf`; defensive assert
  added at `Cast.java` ~296. Full suite: 372 tests, 9 failed (8 pre-existing
  + unrelated, 0 new regressions) — one more pre-existing failure than the
  original "expect 7" estimate: `OctreeLayerLiveRayTest` was already broken
  independently of this branch's work. **New known flaky-order issue found
  during verification, not fixed (out of scope for B, same category as the
  `pConfig` flakiness already documented below):**
  `CastSmallFrustumAirBoundaryPurityTest` passes cleanly in isolation but
  fails when run after `OctreeGrowSweepTest` in the full suite, because
  `MaterialRegistry.baked` (`MaterialRegistry.java:35`) is a
  `static volatile` field with process-wide lifetime and no reset hook;
  `OctreeGrowSweepTest`'s `@BeforeEach` (`OctreeGrowSweepTest.java:46-53`)
  calls `MaterialRegistry.publish(...)` with a local `AIR = new
  Material(1.2, 1.0, 0.5)` and never restores the real registry, so every
  later test in the same JVM sees air impedance 1.2 instead of `DEFAULT`
  (412.0). At least 3 other test classes
  (`OctreeInvalidationTest`, `PatchAggregatorTest`, `OctreeGrowPolarBakeTest`)
  have the same unguarded-publish pattern. Flag for a future cleanup pass;
  not touched here.
- **C** — done, committed (`12a929e`). `Physics.dualDerivedNorm` /
  `dualDerivedAlignment` implement sum-then-normalize of ray + inward
  `(octantCenter − incidentPoint)` with degenerate fallbacks;
  `polarAlignment` signature unchanged. All three `Cast.raycast` polarity
  sites (impedance blend, reflect/permeate gate, frustum telemetry) now go
  through dual-derived via `polarBlendWeight`/`polarizedImpedance` extras
  and a direct call at `lastPolarAlignment`. 9 formula tests added to
  `PhysicsPolarBlendTest` (25/25 green). Full suite: 381 tests; only the
  known pre-existing `OctreeLayerLiveRayTest` failure (plus intermittent
  MaterialRegistry-pollution flakiness on
  `CastSmallFrustumAirBoundaryPurityTest` — unchanged).
- **D, E** — not started. Fully designed below; go in order D → E (do not
  run E concurrently with leftover C work — both touch `Physics.java`, but
  C is done so only E remains on that file).

---

## Task B — polarized 1×1×1 leaf leak (small frustums reflecting on air:air)

### Symptom
Frustums with `frustumSize < 2` spuriously "reflect" on pure air:air
boundaries that should have R=0.

### Root cause (confirmed by direct source reading, not guessed)
A genuine 1×1×1 (finest) octree leaf must **never** carry a polarization
descriptor (`NodeDescriptor.polar() != null`) — polarity is a coarse-aggregate
concept only (baked when a node aggregates ≥2 children of differing
material). Two leaks let it happen anyway:

1. **`Branch.virtualLodCell`** (`src/main/java/dev/thedocruby/resounding/raycast/Branch.java:216-229`)
   copies a coarse node's descriptor **verbatim** into a synthesized virtual
   cell — correct when synthesizing a real LOD aggregate (`lodSize > 1`),
   wrong when `lodSize == 1`, because a coarse *pruned* node
   (`children == null`) can still have `polar() != null` even though it's
   being treated as homogeneous. (Root enabler, NOT to be fixed in this pass:
   `OctreeManager.sameAcousticCell` (~line 326) has a same-`Block`-instance
   shortcut that doesn't check impedance equality, so a "homogeneous" pruned
   node can retain a polar descriptor baked from differing materials. This is
   a known follow-up, out of scope here.)
2. **`Cast.getBlock`** (`src/main/java/dev/thedocruby/resounding/raycast/Cast.java`,
   ~lines 865-880) has an early return `if (branch.size > 1) return branch;`
   which hands back the raw coarse (possibly polarized) branch directly,
   bypassing the single-material `liveLeaf(...)` resolution the rest of the
   method already computes unconditionally. **This is the dominant path**:
   the live-leaf override at `Cast.java` ~line 189
   (`if (lodBranch.size == 1) { Branch live = getBlock(normalized); ... }`)
   always fires when `requestedLod == 1` (i.e., whenever `frustumSize < 2`),
   so `getBlock`'s bug is what actually leaks polarity into small frustums in
   practice.

`Branch.getAtLod(pos, 1)` itself always returns a size-1 node (real leaf or
virtual) — it's not the source of the bug. `Cast.finest = tree.get(queryPos)`
(~line 186) is currently only used for the `virtualLod` debug flag, not
guaranteed size-1 either.

### The fix (two prongs + one guard, no other files touched)

1. **`Branch.virtualLodCell`**: when `lodSize == 1`, do **not** copy
   `descriptor` verbatim — bake a genuine single-material leaf descriptor
   instead, mirroring the pattern `Branch.neighbor`'s coarser-replication path
   already uses (~`Branch.java:339-342`): `imp = material.impedance()` →
   `new NodeDescriptor(imp, imp, imp, NaN, null)` (or `EMPTY` when material is
   null). Keep verbatim copy behavior for `lodSize > 1` (real LOD aggregation
   must be unaffected — this is directly tested, see below).
2. **`Cast.getBlock`**: delete the `if (branch.size > 1) return branch;`
   early return — always return `liveLeaf(block, shape, mat, state)`; those
   values are already computed unconditionally earlier in the method, so this
   costs nothing extra. (Bonus: this also incidentally fixes a latent bug
   where `resolveShape(branch)` on a coarse branch reads shape geometry from
   `branch.start`, a different block than the one actually queried.)
3. **Guard** (defensive, add near `Cast.java:296` where
   `Branch.NodeDescriptor branchDescriptor = branch.descriptor;` is read):
   `assert emissionCast || cellSize > 1 || branchDescriptor.polar() == null : "1³ cell resolved a polarized descriptor";`

**Do not touch the N/E/D forward-survey code** (`FrustumLod.classify`,
`Cast.surveyForward`, the `cellSize > 1` guard around `Cast.java:399-403`).
That code is correct as-is and unrelated to this bug — an earlier proposal to
patch the survey guard was explicitly rejected by the project owner as
treating a symptom, not the cause. Do not "undo" any of that recent work.

Once fixed, CUBE-shaped small cells fall through to plain axis-aligned DDA
with **zero additional code**: `ShapeTraversal.resolve` already returns
`Result.voxel()` for full cubes at `cellSize==1`, and with `polar()` correctly
null, the polarization branches at `Cast.java:299-307` and `Cast.java:351-371`
are simply dead code for that cell — reflectivity falls out of the plain
`impedancesClose`/`Physics.reflection` path, giving air:air R=0 "for free."
Non-cube (partial `VoxelShape`) cells already correctly route through
`ShapeTraversal` regardless of polarity. This is exactly "vanilla DDA through
a simple octant, for CUBE shapes" per the owner's original framing — no
separate architectural change needed for that part.

### Red pass — already done, committed in `6300ac9`

Three failing tests exist in `src/test/java/dev/thedocruby/resounding/raycast/`:

1. **`BranchVirtualLodCellPolarPurityTest.java`** — `lod1_stripsPolarAndUsesUnderlyingMaterialImpedance_notTheBlendedAggregate`
   currently fails: `expected: <null> but was: <(2.0, 0.0, 0.0)>` on
   `cell.descriptor.polar()`. A second test in the same file,
   `lodGreaterThanOne_stillCopiesTheCoarseAggregateDescriptorVerbatim`,
   **already passes** — it's the regression guard for lod≥2 aggregation;
   don't break it.
2. **`CastGetBlockCoarseBranchPurityTest.java`** — fails:
   `expected: <1> but was: <4>` on `result.size`. Uses
   `Mockito.mock(WorldChunk.class, withSettings().extraInterfaces(ChunkChain.class))`
   to satisfy both the `ChunkChain` field type and the `(WorldChunk)` cast
   inside `getBlock`.
3. **`CastSmallFrustumAirBoundaryPurityTest.java`** — full `Cast.raycast()`
   end-to-end regression, fails: `expected: <0.0> but was:
   <0.9993677555011217>` on `cast.lastReflectivity`. Drives a real `raycast()`
   call with `frustumSize = 1.5`, `impededSet = true`, and an exhausted
   `BeamBudget` so `Physics.commitReflect(w)` commits to a spurious near-total
   reflect off what should be plain air.

**Known pre-existing flakiness, not to be fixed in this pass**: `Cast`'s
constructor unconditionally reads `pConfig.frustumGrowthPerBlock`, and
`pConfig` is `null` unless some other test class already initialized it —
this makes `CastBlankBoundaryTest`, `CastImpededTest`, and
`FrustumGrowthGateTest` order-dependent-flaky in isolation. The two
Cast-constructing tests above work around it with a `@BeforeEach`/`@AfterEach`
that builds and installs a real `pConfig`, mirroring the existing
`PrecomputedConfigRayBudgetTest` idiom. Full suite baseline: 372 tests, 10
failed (7 pre-existing unrelated + these 3 intentional new RED failures) —
use this as your "before" number to confirm no new regressions after the
implementation pass (expect 7 failures after B is fixed, i.e. exactly these
3 flip to green and nothing else changes).

### Next action for the new session
Launch a **sonnet, medium-effort** implementation agent: give it this
section verbatim (root cause, the 2 prongs + 1 guard, the exact file:line
targets, the 3 test files and their current failure messages, the "don't
touch N/E/D" instruction, and the "expect 7 failures after, not 0" baseline).
Have it implement the fix, run `nix develop -c ./gradlew test`, confirm the 3
red tests now pass and nothing else regressed, then report back. Then commit
with a message describing the two-pronged fix, and move to Task C.

---

## Task C — dual-derived polarity alignment

### Requirement (verbatim from the project owner)
"If a beam/frustum/ray enters mostly aligned with polarity, but near the
point on the cube where the tangent line is, it should be treated as ~halfway
between the two. Basically, get the average vector of (`<incident point <->
center of octant>` + `<incident angle>`) / 2 (the mean) before running the
polarity alignment calculation." **Approved as-is, no changes requested.**

### Current implementation
`Physics.polarAlignment(Vec3d rayNorm, Vec3d polNorm)`
(`src/main/java/dev/thedocruby/resounding/Physics.java:42-45`) is purely
`dot = rayNorm·polNorm; return dot*dot` — two direction vectors in, no
position/octant context. Three call sites in `Cast.raycast()` (one method
body, lines ~172-447), all of which already have `pposition` (incident
point), `cellBase` (octant min corner), and `cellSize` (octant edge length)
in local scope with **zero new plumbing required**:
- `Cast.java:302` — `polarizedImpedance(branchDescriptor, vector)` → internal
  `polarBlendWeight(descriptor, vector)` (`Cast.java:528-541`) →
  `Physics.polarAlignment(vector.normalize(), pol.normalize())` at
  `Cast.java:540`. Feeds `Physics.blendImpedance`, which sets `newImpedance`
  — **this affects real R/T physics, not just cosmetics.**
- `Cast.java:353` — same `polarBlendWeight(branchDescriptor, vector)`, feeds
  the notable-interaction/commit-reflect gate (`Cast.java:342-371`).
- `Cast.java:442-444` — `this.lastPolarAlignment = Physics.polarAlignment(...)`,
  feeds `FrustumLod.nextFrustumSize`'s growth/shrink blend.

### Approved design

**Sign, resolved definitively**: `octantCenter - incidentPoint`, normalized
— pointing **inward**, from the crossing point toward the cell center. Proof
by limit cases (both confirmed by exact closed-form arithmetic, not just
intuition):
- Dead-center entry (ray aimed straight at center): `C - P` is parallel to
  the ray → blend is a no-op → alignment equals the current pure-angle
  result, unchanged. (The reversed sign `P - C` degenerates to the zero
  vector exactly in this case — proof the sign must be `C - P`.)
- Tangent entry with a fully pol-aligned ray: `C - P` is nearly perpendicular
  to the ray; the normalized sum is the exact angular bisector, 45° off the
  ray. Alignment becomes `cos²45° = exactly 0.5` — precisely the "~halfway"
  spec, with no fudge factor needed.
- Degenerate guard: if `(C - P)` has `lengthSquared() < 1e-12`, or the sum
  `rayNorm + normalize(C-P)` has `lengthSquared() < 1e-12`, fall back to raw
  `normalize(rayNorm)`.

**API** — add to `Physics.java`, alongside `polarAlignment` (do **not**
change `polarAlignment`'s existing signature; it's tested and stays as the
final-stage primitive):

```java
/** Blended "effective ray norm": normalize(rayNorm + normalize(octantCenter - incidentPoint)).
 *  Falls back to normalize(rayNorm) when either the offset or the sum is degenerate. */
public static Vec3d dualDerivedNorm(@NotNull Vec3d rayNorm, @NotNull Vec3d incidentPoint, @NotNull Vec3d octantCenter)

/** polarAlignment(dualDerivedNorm(...), polNorm) — position-aware alignment. */
public static double dualDerivedAlignment(@NotNull Vec3d rayNorm, @NotNull Vec3d incidentPoint, @NotNull Vec3d octantCenter, @NotNull Vec3d polNorm)
```

`dualDerivedNorm` should normalize `rayNorm` internally (don't rely on
call sites pre-normalizing). Implement as sum-then-normalize (mathematically
identical to average-then-normalize).

**Uniform application**: apply at **all three** call sites (302, 353,
442-444) — the impedance-blend and reflect/permeate-gate sites must never
disagree with each other (they currently share one `w`), and the frustum-
growth telemetry site must reflect the same formula for debugging to make
sense. One helper, three call sites:
- Compute `Vec3d octantCenter = cellBase.add(cellSize * 0.5, cellSize * 0.5, cellSize * 0.5)`
  once in `raycast()` after `cellBase`/`cellSize` are established.
- Change the private `Cast.polarBlendWeight(descriptor, vector)` →
  `polarBlendWeight(descriptor, vector, incidentPoint, octantCenter)`
  (private, no external API break), swapping its `Physics.polarAlignment`
  call for `Physics.dualDerivedAlignment`. `polarizedImpedance` gets the
  same two extra params and forwards them.
- Site 442-444 calls `Physics.dualDerivedAlignment(vector.normalize(), pposition, octantCenter, polar.normalize())`
  directly, using whatever `vector` is current at that point in the method
  (it may have been reassigned by `permeationBend` earlier in the same call
  — that's correct: post-bend alignment is what the growth blend should see).

Existing 3 tests in `src/test/java/dev/thedocruby/resounding/PhysicsPolarBlendTest.java`
(lines 23, 28, 33 — bare `Vec3d` pairs into `polarAlignment`) must stay green
untouched, since that method's signature doesn't change.

### Red-pass test list (write into `PhysicsPolarBlendTest.java`)

Fixture: `cellBase (0,0,0)`, `cellSize 2`, so `center C = (1,1,1)`.
`DELTA = 1e-9`.

1. `dualDerivedAlignmentDeadCenterEntryMatchesPureAngle` — ray `(1,0,0)`, P
   `(0,1,1)` (center of −X face, `C−P ∥ ray`). Assert
   `dualDerivedAlignment(ray, P, C, pol) == polarAlignment(ray, pol)` for pol
   `(1,0,0)` (→1.0) and pol `(s,s,0)`, `s=√2/2` (→0.5).
2. `tangentEntryWithFullyAlignedRayIsExactlyOneHalf` — ray `(1,0,0)`, pol
   `(1,0,0)`, P `(1,2,1)` (center of +Y face; `C−P = (0,−1,0) ⊥ ray`). Assert
   `0.5`.
3. `tangentEntryWithPerpendicularPolIsAlsoOneHalf` — same geometry, pol
   `(0,1,0)`. Blended `= (1,−1,0)/√2`, dot² `= 0.5`.
4. `sumThenNormalizeFormulaExactValue` — ray `(1,0,0)`, P `(0,2,1)` (top edge
   of −X face; `C−P = (1,−1,0)/√2`), pol `(1,0,0)`. Assert
   `(2 + Math.sqrt(2)) / 4` (≈0.8535533906). Pins the exact formula against
   alternatives (e.g. averaging angles instead of vectors).
5. `degenerateOffsetFallsBackToRawRay` — P `== C` exactly. Assert equals
   `polarAlignment(rayNorm, pol)`.
6. `antiParallelSumFallsBackToRawRay` — ray `(1,0,0)`, P `(2,1,1)` (+X face
   center; `C−P = (−1,0,0)`, sum = 0). Assert equals
   `polarAlignment(rayNorm, pol)`, no NaN.
7. `dualDerivedNormIsUnitLength` — loop 3-4 assorted ray/P combos; assert
   `dualDerivedNorm(...).length() == 1.0` within DELTA.
8. `dualDerivedNormNormalizesRayInput` — ray `(2,0,0)` vs `(1,0,0)`, same
   P/C: identical result (pins internal normalization).
9. `polSignInvarianceStillHolds` — fixture from test 4 with pol `(−1,0,0)`:
   same `(2+√2)/4` (squared dot, side-agnostic).

Cast-level wiring correctness (that 302/353/442 all switched over) doesn't
need its own new test harness — the Physics tests pin the formula exactly,
and the wiring is a mechanical 3-line change per call site.

### Done
Committed in `12a929e`. Stubs → real `dualDerivedNorm`/`dualDerivedAlignment`
+ Cast wiring at all three sites. Move to Task D.

---

## Task D — K-key focused-ray "kapture" (dead key + flash feedback)

### Requirement (final, as corrected by the owner)
"Make it a dead key when nothing has been captured. Provide visual feedback
in the form of the ray flashing in & out of visibility (leave the frustum
octants rendered the whole time) 3x in 1.5s. white -> transparent -> white ->
transparent -> white -> transparent -> white -> no more flashing. The
technical direction you have [for the log-dump format] is fine."

So: **K is a pure read** of existing `CaptureBuffer` data for the currently
focused ray. If empty → literally nothing happens (no arming, no warning, no
side effect at all — true dead key). If non-empty → log it (format below) and
trigger the flash.

### Existing infra (verified, reuse as-is)
- Keybindings: `src/main/java/dev/thedocruby/resounding/debug/DebugKeybinds.java` —
  Fabric `KeyBinding` fields, polled per-tick via
  `ClientTickEvents.END_CLIENT_TICK.register(...)` (~line 63) with
  `while (BINDING.wasPressed())` loops. `B` toggles bounce-ray display; `J`
  calls `OctreeLayer.cycleLiveFrustumRay()`, which sets
  `OctreeLayer.selectedRayIndex` (int, `OctreeLayer.java:99`, exposed via
  `selectedRayIndex()`, `-1` = none focused). `GLFW_KEY_K` is unused.
- `CaptureBuffer`/`C` key: `CaptureBuffer.INSTANCE.isCapturing()` toggled by
  `C`. While capturing, `Renderer.addSoundBounceRay`
  (`src/main/java/dev/thedocruby/resounding/raycast/Renderer.java:19-51`)
  offers a `CaptureBuffer.CapturedRay` record (`CaptureBuffer.java:22-40`:
  `start, end, color, soundEventId, rayIndex, bounceIndex, material,
  reflectivity, transmission, power, priorImpedance, branchSize,
  materialLabel, terminated`) per bounce, gated on `pConfig.dRays`.
  `CaptureBuffer.segmentsForRayIndex(rayIndex)` returns all bounce records
  for one ray — this is exactly what K reads.
- **Key discovery**: the focused ray is **already rendered as a solid white
  overlay** — `ACTIVE_RAY_COLOR = 0xFFFFFFFF` (`BounceRayLayer.java:27`) via
  `renderFocusedWhiteRay()` → `renderActiveRay()`
  (`BounceRayLayer.java:113-125, 158-179`), on a dedicated no-depth-test
  buffer, completely separate from `OctreeLayer`'s octant-box rendering (own
  layer/buffers). So "flash" = literally toggling whether this existing
  render call happens, not a recolor. `OctreeLayer` is untouched by any of
  this — octant boxes keep rendering the whole time, automatically, since
  nothing here changes `OctreeLayer` at all.
- `logBounceIfNeeded` (`Engine.java:446-479`) is the formatting precedent —
  reuse its `%.1f`/`%.3f`/`formatPos` conventions for the new log format.

### Approved design

**Data model**: extend `CapturedRay` (`CaptureBuffer.java:22-40`) with 5
fields (unconditional, benefits regular `C`-capture too — no parallel
record type, avoids duplicating the offer plumbing):
- `double resolvedImpedance` — `cast.lastResolvedImpedance`
- `double polarAlignment` — `cast.lastPolarAlignment`
- `double frustumSize` — `cast.frustumSize`
- `@Nullable String blankReason` — `cast.lastBlankReason == NONE ? null : cast.lastBlankReason.name()`
- `boolean shapeMode` — `cast.lastShapeMode`

To populate these without blowing out `Renderer.addSoundBounceRay`'s
positional-param count, change that method to accept the `Cast` object
directly (already in scope at its call site,
`Engine.emitDebugSegment`/`Engine.java:582`) and snapshot fields inside
`Renderer`.

**Log format** — new small class `src/main/java/dev/thedocruby/resounding/debug/KaptureLogger.java`,
static `dump(int rayIndex, List<CapturedRay> bounces)`, one
`Utils.LOGGER.info` per bounce (not one multi-line blob — matches existing
grep-ability), framed by a header/footer line:
```
Resounding KAPTURE ray #{rayIndex} sound={soundEventId} bounces={n} ---
Resounding KAPTURE: ray #{} bounce #{} node={}³ mode={SHAPE|VOXEL} start={pos} end={pos} material={label|?} Zprev=%.1f Z=%.1f R=%.3f T=%.3f power=%.1f polar=%.3f frustum=%.2f[ blank={reason}][ TERMINATED]
Resounding KAPTURE ray #{rayIndex} --- end
```
No `dLog`/id gating — K is an explicit user action, always logs when there's
data.

**Flash mechanism** — put the state on `BounceRayLayer` (it owns the render
decision being modified; `OctreeLayer` stays untouched):
```java
private volatile long flashStartMillis = -1L;  // -1 = not flashing
private volatile int flashRayIndex = -1;
public void startKaptureFlash(int rayIndex) { flashStartMillis = System.currentTimeMillis(); flashRayIndex = rayIndex; }
```
Storing `flashRayIndex` prevents a mid-flash `J`-press (changing focus) from
transferring the flash to the newly-focused ray. Set from the K handler in
`DebugKeybinds` after `KaptureLogger.dump(...)`. Read in
`renderFocusedWhiteRay()`: if flashing and the active ray matches
`flashRayIndex`, and the current phase is "transparent," skip
`renderActiveRay(...)` entirely for that frame; once elapsed ≥ 1500ms, reset
`flashStartMillis = -1` (steady visible from then on).

**Timing math** — pure, no MC imports, its own tiny class (e.g.
`KaptureFlash` in the `debug` package) so it's unit-testable without a
Minecraft client bootstrap. 7 equal phases over 1500ms, integer math:
```java
static boolean isFlashing(long elapsedMs) { return elapsedMs >= 0 && elapsedMs < 1500; }
static int phaseIndex(long elapsedMs)     { return (int) ((elapsedMs * 7) / 1500); }  // 0..6
static boolean isVisible(long elapsedMs)  { return !isFlashing(elapsedMs) || phaseIndex(elapsedMs) % 2 == 0; }
```
Phases 0/2/4/6 = white (visible), 1/3/5 = transparent (skip render); at
≥1500ms steady visible.

**K-handler decision logic** — extract into a testable static so tests don't
need a Minecraft client, e.g. `KaptureAction.execute(int selectedRayIndex,
List<CapturedRay> bounces, Consumer<String> logSink) : boolean` (return value
= "should a flash start"). `DebugKeybinds` wires the boolean return to
`startKaptureFlash`.

### Red-pass test list

**`KaptureFlashTest`** (pure):
- `t0_isWhite`: `isFlashing(0)`→true, `phaseIndex(0)`→0, `isVisible(0)`→true.
- `t107_stillFirstWhite`: `phaseIndex(107)`→0, `isVisible(107)`→true.
- `t215_firstTransparent`: `phaseIndex(215)`→1, `isVisible(215)`→false.
- `t750_middleTransparent`: `phaseIndex(750)`→3, `isVisible(750)`→false.
- `t1499_lastWhite`: `phaseIndex(1499)`→6, `isVisible(1499)`→true.
- `t1600_steadyNotFlashing`: `isFlashing(1600)`→false, `isVisible(1600)`→true.
- `boundaries`: `phaseIndex(214)`→0 (214·7=1498<1500), `phaseIndex(215)`→1;
  `isFlashing(1500)`→false.

**`KaptureActionTest`**:
- `deadKey_noFocus`: `selectedRayIndex = -1` → returns `false`, `logSink`
  never invoked.
- `deadKey_emptyCapture`: valid index, empty list → returns `false`, no log
  lines. **This is the core "dead key" requirement — no side effects at all.**
- `withData_logsAndFlashes`: index 5, 3 synthetic `CapturedRay`s → returns
  `true`; `logSink` received exactly 3 lines (+ header/footer), each line
  matching the approved format (regex-assert one representative line
  including `polar=`/`frustum=`).

**`CapturedRay` round-trip test**: construct with the 5 new fields; assert
accessors, and that the `Renderer`-side snapshot maps
`lastBlankReason=NONE → null` and non-NONE → its `.name()`.

Do not force a Minecraft-client bootstrap test for `BounceRayLayer`'s actual
render-skip behavior (needs GL context) — the pure timing/decision logic
above is the testable core; the render wiring itself is glue, verified by
manual in-game testing (see `run` skill) after implementation, not by a unit
test.

### Next action
Red-pass agent (sonnet, medium): create `KaptureFlash` + `KaptureAction`
skeletons (stub bodies) and the 4 test classes above; confirm they fail
correctly. Implementation agent: real `KaptureFlash`/`KaptureAction` logic,
`CapturedRay` field extension + `Renderer`/`Engine` plumbing, `KaptureLogger`,
`BounceRayLayer` flash-state fields + `renderFocusedWhiteRay()` gate, and the
new `K` `KeyBinding` + tick-loop wiring in `DebugKeybinds.java`. Verify suite
green, then manually smoke-test in-game (B → J to focus a ray → C to capture
→ let it finish → K → confirm log lines appear and the ray visibly flashes 3x
then stays solid; also confirm K before any capture is a true no-op). Commit,
move to Task E.

---

## Task E — Patient Frustum Growth (double-cover avoidance + free graze-refraction)

**Explicitly NOT about beam-splitting/`BeamBudget` tiers** — that's a
separate, unrelated, later feature. Ignore `BeamBudget` entirely for this task.

### Requirement (verbatim worked example from the owner — this IS the spec)

> Right now, I'm concerned about a ray being in the former position and
> growing into the latter:
> ```
> // two 2^3 (in 2D) octants next to each other
> XXOO <-- former
> *OOO <-- former
>
> **OO <-- latter
> **OO <-- latter
> // it should not grow, instead it should:
> // This is where the lossless reflection happens: (should not count as a
> // bounce, gets marked as a refraction but uses a different angle)
> XXOO <-- lossless reflection
> O*OO <-- lossless reflection
> // ^ it examined the parent octant, and saw the polarity (vertical in this
> // 2D example), then reflected (roughly, just spitballing numbers here) to
> // a slope of y=-.3x, and then on the next step, grew into:
> XX** <-- now that it's not re-covering its past space via a parent octant
> OO** <-- it actually grows. Between *OOO and O*OO it should not grow at
> // all, but between O*OO and OO** it should, and should act like it (if it
> // passes the threshold of 2.0, that is)
> ```
> This will cause frustums to glide along walls more often (like real waves do).

Clarifications from the owner (binding):
- Use the **plain** `Physics.polarAlignment` here, NOT the Task-C dual-derived
  version — "the ray will already be within the octant, so the dual blended
  version will produce incorrect behavior, even with normalization."
- The double-cover concern is pure **worldspace footprint overlap** — nothing
  to do with splitting/notable-interaction/`BeamBudget`.
- The alignment/double-cover check runs on **every growth attempt** (every
  step), not just at tier/threshold boundaries.
- "No extra loss" means: ordinary permeation cost for traversing the cell
  still applies as normal — only the parent-polarity redirection itself is
  free (no extra impedance/reflection loss, no extra frustum-size shrink
  beyond what growth already would or wouldn't have applied).

### Current growth mechanism (for context)
`Cast.applyFrustumStep` (`Cast.java:1014-1024`) calls
`FrustumLod.nextFrustumSize` (`FrustumLod.java:122-133`) unconditionally on
every permeate step: `grown = previousSize + growthPerBlock*stepDistance`;
`blend = leftoverEnergy² + (1-leftoverEnergy²)*polarAlignment`; `next =
blend*grown`. No existing check for whether growing would double-cover
already-passed space. `polar = highDir - lowDir` (`Polarization.java:147`)
points from the open half toward the solid half.

### Approved design

**1. The double-cover predicate** — new pure static in `FrustumLod.java`:
```java
public static boolean wouldDoubleCover(
        Vec3d cellBase, int cellSize, Vec3d exitPos,
        @Nullable Vec3d polar, double candidateSize, double currentSize)
```
Logic:
- `polar == null || polar.lengthSquared() <= 1e-12` → `false`.
- `candidateSize <= currentSize` (no growth attempted, e.g. a reflect leg) →
  `false`.
- `a` = argmax `|polar component|`; `s` = sign of `polar[a]` (+ points toward
  the solid half).
- Polarity mid-plane: `mid = component(cellBase, a) + cellSize / 2.0`.
- Clearance from the exit point to the boundary, measured toward solid:
  `clearance = max(0, s > 0 ? mid - exitPos[a] : exitPos[a] - mid)`
  (negative = already past the plane → clamp to 0, always triggers).
- Return `candidateSize / 2.0 > clearance`.

Sanity-checked against the worked example (left octant `cellBase=(0,0)`,
`cellSize=2`, wall on top, `polar ≈ (0,+1)`, ray at `y≈0.5`): `mid=1.0`,
`clearance=0.5`; candidate footprint ≥2.0 → half=1.0 > 0.5 → **triggers**, at
both the `*OOO` and `O*OO` positions — matches "between `*OOO` and `O*OO` it
should not grow at all." Once resolved in the open right octant
(`polar == null`), predicate is `false` and growth proceeds normally — no
special "resume" logic needed; `FrustumLod.stepForSize`'s existing slot
boundaries (the 2.0 threshold referenced in the example) handle that
already, untouched.

Candidate size at the call site: `candidate = frustumSize +
pConfig.frustumGrowthPerBlock * pdistance` (`pdistance` = transmit-leg length
already in scope in `Cast.raycast`).

**2. The free bend** — `Physics.permeationBend` (existing, sign-copy only)
**cannot be reused** — it preserves each component's magnitude exactly, so a
slope of 0.6 can only ever become ±0.6, never ~−0.3 as the example requires.
New method in `Physics.java`:
```java
public static Vec3d grazeBend(Vec3d ray, Vec3d rayNorm, Vec3d polNorm) {
    double n = polNorm.dotProduct(rayNorm);
    if (n <= 0) return ray;                      // already heading away from solid
    double align = n * n;                        // this IS Physics.polarAlignment(rayNorm, polNorm) — reuse it, don't recompute
    Vec3d tangent = rayNorm.subtract(polNorm.multiply(n));
    Vec3d bent = tangent.subtract(polNorm.multiply(n * (1.0 - align)));
    return bent.normalize().multiply(ray.length());  // magnitude preserved exactly
}
```
Verified against the example: ray `(1, 0.6)` normalized ≈ `(0.857, 0.514)`,
`polNorm=(0,1)` → `n=0.514`, `align≈0.264`, bent normal component ≈
`-0.514*0.736 ≈ -0.378` → resulting slope ≈ `-0.44`, in the owner's "roughly
−0.3" ballpark (owner said "just spitballing numbers," so an exact match
isn't required/possible — the shape of the behavior is what's being pinned:
partial flip, damped by alignment, not a full mirror). Head-on rays
(`align→1`) collapse toward pure tangent glide — this is the "glide along
walls" effect the owner wants. When the predicate holds but `n <= 0` (ray
already heading away from the solid half), still defer growth but skip the
bend (calling `grazeBend` is a no-op in that case anyway per the guard).

**3. Placement** — predicate + bend go **inside `Cast.raycast`**, on the
permeate path, immediately after the existing polarized-LOD block
(`Cast.java:351-371`, where `branchDescriptor.polar()`, `vector`, `cellBase`,
`cellSize`, `pposition` all already coexist — this is the same place the
existing two `permeationBend` calls already mutate `vector`) and before
`transmitted` is computed (`Cast.java:424-426`). **Deferral** goes inside
`Cast.applyFrustumStep` via a new boolean field, e.g. `lastGrowthDeferred`
(reset at the top of each `raycast()` call, alongside `lastBoundaryResolved`):
when set, `applyFrustumStep` **hard early-returns** `frustumSize` unchanged
— no growth AND no shrink blend (do not fake `leftoverEnergyCoefficient =
1.0`; that still runs the blend math and invites drift — an exact early
return is correct and simpler). `Engine.advanceFrustumSize` and its two call
sites in `Engine.raycast` stay byte-identical — no changes needed there.

**4. Accounting — confirmed free "by construction," minimal wiring needed**:
- **No bounce**: lives entirely on the transmit leg; `Engine`'s
  `bounceCount`/`reflected` counters only increment inside the
  `reflect.apply(...)` branch (`Engine.java:367-369`), and the transmit
  branch already resets `reflected = 0` (`Engine.java:429`) — no Engine
  change needed.
- **No reverb hit**: `recordReflectHitIfAny` gates on
  `cast.reflected.power() > 0` — a graze-refraction produces no reflected
  power of its own, so it's naturally excluded.
- **Normal permeation cost still applies**: `transmission =
  transmissionForBoundary(...)` runs completely unmodified for this cell —
  only the direction bend and the growth-shrink math are affected, not the
  ordinary energy transmission calc.
- Tag the event for debug/log visibility: add a `lastFreeRefraction` boolean
  field on `Cast` (parallel to `lastBlankReason`) so `debugTail`/future
  `KaptureLogger` output (Task D) can render it distinctly from an ordinary
  reflect or permeate.

### Red-pass test list

New files: `FrustumLodDoubleCoverTest.java`, `PhysicsGrazeBendTest.java`, plus
additions to whatever existing `Cast`/`Engine` frustum-step test harness
exists (mirror `FrustumGrowthGateTest`'s idiom).

1. **Predicate triggers on worked geometry**: `cellBase=(0,0,0)`,
   `cellSize=2`, `polar=(0,1,0)`, `exitPos=(0.9,0.5,0)`, `current=1.0`,
   `candidate=2.0` → assert `true`. Variant at `exitPos.x=1.5` (`O*OO`
   position) → still `true`.
2. **Clears one octant later**: same call with `polar=null` → `false`; also
   a fixture with `cellBase=(2,0,0)` (the neighboring open octant),
   unpolarized descriptor → `false`; and a polarized-but-clear case
   `exitPos=(0.5,0.2,0)`, `candidate=0.3` → `false` (half `0.15` <
   clearance `0.8`).
3. **No-growth guard**: `candidate == current` (reflect leg, no growth
   attempted) → `false` even at zero clearance.
4. **Past-plane clamp**: `exitPos[a]` already beyond `mid` on the solid
   side, any positive growth → `true`.
5. **`grazeBend` direction/magnitude**: ray `(1,0.6,0)`, pol `(0,1,0)` →
   assert `result.y < 0`, `|result.y| < 0.6`, `|result| == |ray|` within
   `1e-9`, `result.x > 0` unchanged sign; assert `result.y / result.x`
   within `[-0.5, -0.25]` (locks the "partial, not mirror" band). Ray moving
   away from solid (`(1,-0.6,0)`, i.e. `n<=0`) → returned **unchanged**.
6. **Deferral is exact**: set a test `Cast`'s `lastGrowthDeferred = true`,
   `frustumSize = 1.7`, call `applyFrustumStep(dist=5, growth=0.4,
   leftover=0.3, permeated=true)` → assert `frustumSize == 1.7` exactly (no
   growth, no shrink at all). With the flag `false` → result equals plain
   `FrustumLod.nextFrustumSize(...)` (unchanged behavior when not deferred).
7. **Accounting untouched**: an `Engine`-level fixture forcing one
   deferred-refraction transmit step → assert `bounceCount`/reflect counter
   unchanged, no new reverb `Hit` recorded by that step,
   `cast.lastTransmission` equals plain `transmissionForBoundary` for that
   cell (i.e. no extra loss), and `lastFreeRefraction == true` for that step
   while `lastGrowthDeferred` resets cleanly on the next ordinary cast.

### Next action
Red-pass agent (sonnet, medium): add `FrustumLod.wouldDoubleCover` and
`Physics.grazeBend` stubs + the 7 tests above; confirm correct failures.
Implementation agent: real predicate + bend logic, `Cast.lastGrowthDeferred`/
`lastFreeRefraction` fields, wiring into `Cast.raycast` (right after
`Cast.java:351-371`) and `Cast.applyFrustumStep`. Verify full suite green,
commit. **This is the last task — after it lands, the full A–E arc is done.**

---

## Order of operations for the new session

1. Confirm clean tree (`git status --short` should be empty; last commit is
   `12a929e` — Task C done).
2. ~~Task B~~ / ~~Task C~~ — done.
3. Task D: red pass → verify fails correctly → implementation pass → verify
   → commit.
4. Task E: red pass → verify → implementation pass → verify → commit.
5. Each implementation/red-pass agent: **sonnet model, medium thinking
   effort**, given the relevant section of this doc verbatim as its brief
   (file:line references, exact formulas, exact test lists) — they should not
   need to re-derive anything, only verify against current source (line
   numbers may have drifted slightly from earlier fix passes — always
   `agentgrep grep`/`Read` to confirm current line numbers before editing,
   don't blindly trust this doc's line numbers if a prior task's diff moved
   things around).
