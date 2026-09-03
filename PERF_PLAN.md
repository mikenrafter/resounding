# Performance pass — plan

Source: audit of profiler coverage, code duplication, and missed branchless/common
optimizations in the raycasting/octree engine (raycast/, Engine.java, Physics.java,
OctreeManager.java, Branch.java). Decisions below are final for this pass; items marked
TODO-only are explicitly deferred pending profiling data, not implemented now.

## 1. Profiler legibility (spark)

The engine currently has zero real instrumentation — MC's `Profiler` only wraps debug-overlay
rendering, and the only timer is one opt-in `System.nanoTime()` blob around the whole
`play()` call. Goal: make spark/F3 legible without touching engine correctness.

- [ ] `OctreeManager.octreePool`: give it a named `ThreadFactory` (e.g. `resounding-octree-%d`)
      so flame graphs show real thread names instead of `pool-N-thread-M`.
- [ ] `Engine.play()`: wrap the three real phases with `mc.getProfiler().push(...)`/`pop()`
      on the calling thread (same push/pop-in-try/finally pattern already used in
      `debug/DebugPicker.java:47-108`): `resounding_eval_env`, `resounding_process_env`,
      `resounding_set_env`.
- [ ] `Engine.evalEnv()`: push/pop around the seed-ray parallel stream
      (`resounding_reflection_rays`) and around `throwOcclRay` (`resounding_occlusion_ray`).
      Both calls block on the calling thread before returning, so this is safe — push/pop
      never crosses into the parallel worker threads themselves (MC's `Profiler` is not
      thread-safe; do not push/pop from inside `IntStream.parallel()` lambdas or the
      octree-rebuild pool).

## 2. Dedupe: Cast.java null-coalescing fallback (scope: Cast.java only)

Three near-identical fallback blocks — `branch.shape ?? state.getCollisionShape(...)` and
`branch.material ?? material(state)` — at lines ~179-182, ~228-231, and ~736-744.
`getBlock()`/`liveLeaf` (811-826) is a different pattern (always-fresh lookup, not a
null-coalesce) and is out of scope.

- [ ] Extract `private VoxelShape resolveShape(Branch branch)` and
      `private Material resolveMaterial(Branch branch, BlockPos pos)`, replace all three
      call sites.

## 3. Optimizations

- [ ] **#1 HashMap → flat octree storage**: NOT implemented this pass. Add a `// TODO(perf)`
      comment on `Branch.leaves` (raycast/Branch.java) flagging: replace `HashMap<Long,Branch>`
      with a flat `children[8]`-style array keyed by octant index/depth; note that the
      recursive `Branch.get()`/`getAtLod()` traversal API likely needs to change shape too;
      flag that this needs memory *and* CPU profiling before committing to an approach.
- [ ] **#2 Vec3i allocation churn → flyweights**: `Cast.getStepPair` and `Cast.entryPlane`
      both allocate a fresh `new Vec3i(±1/0, 0, 0)`-shaped object every call via
      `MathHelper.floor(-Math.signum(...))`. Replace with 6 cached `static final Vec3i`
      unit-axis constants (plus `Vec3i.ZERO`) selected via small `xUnit/yUnit/zUnit(double)`
      helpers — eliminates the allocation entirely (MC's `Vec3i`/`Vec3d` are immutable, so
      true mutable scratch buffers aren't possible without a new vector type; that's out of
      scope for this pass — flyweight caching gets the same allocation win for this
      specific finite-value-set case).
- [ ] **#3 boxed `Double` → primitive + boolean**: `Cast.impeded` becomes
      `double impeded` + `boolean impededSet`. `lastReflectivity`/`lastTransmission` become
      primitive `double` fields sharing one `boolean lastBoundaryResolved` flag (they are
      always assigned together, at the single site `Cast.java:356-357` — verified no other
      write site exists). Update all `!= null`/`== null` call sites in `Cast.java` and
      `Engine.java` (raycast loop, debug logging, `throwOcclRay`). Do NOT touch
      `enteredFrom` (separate sentinel, not in scope).
- [ ] **#4 Redundant neighbor/impedance lookups**: `Cast.surveyForwardMap` and
      `Cast.forwardNeighborImpedance` each independently walk the same N/E/D octree
      offsets via `lodAt()`. Merge into one `surveyForward(...)` returning a small
      `ForwardSurvey(interaction, wallImpedance)` record computed from a single walk;
      update the one call site in `Cast.raycast()`.
- [ ] **#5 Boxed `Set<Pair<Vec3d,Integer>>` → array**: ray ids are dense (`0..nRays-1`) and
      will stay that way. `Engine.rays` becomes `Vec3d[] rays` (direction only, id = index).
      `updateRays()` builds the array directly instead of `.collect(Collectors.toSet())`;
      `evalEnv()` iterates by index and reconstructs `Pair<Vec3d,Integer>` only at the
      `Engine.raycast(...)` call boundary (that method's signature stays `Pair`-based since
      it's also called elsewhere with a synthetic `-1` id for occlusion sampling — not
      touched this pass).
- [ ] **#6 Branchless axis min-select**: `Cast.getStepPair`'s sequential
      `if (ystep < coefficient) {...}` / `if (zstep < coefficient) {...}` — rewrite as
      ternary-based argmin (`boolean yWins = ystep < xstep; ...`) so the JIT can turn it
      into cmov, and only resolve the winning axis's `Vec3i` (via the #2 flyweights) once
      the argmin is known, instead of allocating inside each branch.
- [ ] **#7 sqrt-then-compare guards**: NOT implemented this pass. Add `// TODO(perf)`
      comments on `Cast.nudgeEmissionExit` and `Cast.nudgeReflectOrigin` (both do
      `double length = vector.length(); if (length < threshold) ...`) flagging that
      `lengthSquared() < threshold²` would skip the sqrt on the common early-exit path —
      needs profiling to confirm sqrt is actually hot before switching.

## Ordering / verification

Implement in the order above (profiler wrapping → dedupe → #4 → #3 → #5 → #2+#6 together,
since they touch the same `getStepPair`/`entryPlane` code). Run `core-tests` and
`src/test` after the `Cast.java`-touching changes (dedupe, #3, #4, #2+#6) since that file
has a history of subtle correctness regressions. Compile the whole project at the end.
