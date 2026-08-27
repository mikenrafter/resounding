# Tagging system restoration — plan v2.1 (final)

Repo: `resounding`, branch `octree-testing-1.21`. Fabric / Minecraft 1.21 / Java 21 / Loom 1.7.1.

Ledger: attractor `A-01 material-resolution`; stressors `S-01..S-06`; purposes `P-01..P-06`.
Reviewed adversarially twice; v2.1 applies the second round's required changes.

---

## 0. Environment facts (verified on this machine, not assumed)

| Fact | Status |
|---|---|
| `./gradlew build` (cold Loom) | **Succeeds, 1m39s.** Lane 2 go/no-go gate is **GREEN**. |
| `java` on PATH | **Absent.** `JAVA_HOME=/nix/store/qqngq35hqpiqm5g5w4wgjj2aam09qxif-openjdk-21.0.12+8` must be exported for every Gradle invocation. Only JDK 21 on the box. |
| Gradle 8.8 distribution | **Now cached** (downloaded during planning). Both lanes pin 8.8; no second version, no toolchain auto-provision (needs foojay + network, unavailable by default). |
| Gradle 9.1.0 | Must **not** be used — Loom 1.7.1 predates it. |
| `Block.getName()` | `net.minecraft.text.MutableText` — **defect 1 confirmed against the compiled jar.** |
| `Block.getTranslationKey()` | `String` — the two domains provably cannot meet. |
| `Registries.BLOCK` | `DefaultedRegistry<Block>` — has `getDefaultId()` and a total `get(Identifier)`; gives the non-null fallback contract for free. |
| `ResourcePack` | `extends AutoCloseable` with `close()` — the never-closed pack is a real handle leak. |
| `AbstractBlockState.streamTags()` | `Stream<TagKey<Block>>` — as assumed. |
| `IndexedIterable` | exposes `ABSENT_RAW_ID` and `size()`. |

---

## 1. Defects

Numbering is stable across revisions. Classification reflects both review rounds.

### A. Identifier-domain incoherence (S-01)
1. `MaterialRegistry.java:39` — `getOrDefault(state.getBlock().getName(), …)` passes a `MutableText` to a
   `HashMap<String,Material>`. Compiles via `getOrDefault(Object,V)` erasure. **Every lookup misses**, and
   the `"air"` fallback is absent, so a `@NotNull` method returns `null`.
2. `Cache.java:51,54` — translation keys, tag ids, and pack names share one namespace; `:69` merges
   block-keyed shells into the pack-material map.

### B. Resolution engine (S-02, S-06)
3. `TagRegistry.java:48,59` — memoize lambda closes over loop variable `name`, not the memoized key;
   recursion writes the reverse index **under the wrong tag**. The file's own comment at `:43-45` warns
   against this. (The wrong-tag *read* at `:48` is masked by defect 4; the *write* at `:59` is live.)
4. `TagRegistry.java:46,48` — 4-arg `memoize` ⇒ `remove=true`; `:48` reads the entry *after* removal and
   always gets the empty default. **Explicit `blocks` lists are unconditionally discarded.**
5. `Cache.java:55-61` — registry scan clobbers pack-defined `patterns`/`tagPatterns`/`tags`, and allocates
   a `RawTag` per (block × tag).
6. `Utils.java:155,159` — `granularFilter` null-unsafe. **Latent: no live NPE path** (the two
   `new RawTag(null,…)` sites are `getOrDefault` defaults, never stored). Fix by boundary normalization.
11. `Utils.java:60-76` — `null` encodes cycle / absent / legitimately-null alike; the in-progress `null`
    is left in `out` as a **permanent negative cache**, and `remove=true` destroys the input so retry is
    impossible.

### C. Numeric / robustness (S-05)
7. `Property.java:11,71` — `int count` with `this.count += count` on a `double` parameter; implicit
   narrowing **corrupts fractional compositions** (0.5 → 0).
8. `MaterialRegistry.java:166,179-186` — `:166` defaults a null `composition` to `new Double[0]`, so **any**
   material with solutes and no composition array — the common case, composition being optional — AIOOBEs on
   the **first** iteration.
9. `MaterialRegistry.java:275-280` — `composition[0]` cannot AIOOBE from the current caller. **The real
   defect is a coverage hole:** `Cache.java:50-63` only admits blocks with ≥1 registry tag, so **untagged
   blocks never get a material at all.**
10. `Utils.java:187-189` — `token()` captures a bare `TypeVariable`, erased by Gson to `Object`. Works by
    accident; **Gson 2.11+ hard-throws** on this pattern. `MaterialRegistry.java:44-48` CCEs on the
    `Integer` defaults; `Utils.java:122` leaks the reader; `:128` NPEs on an **empty** (not missing) file.

### D. Absent input (S-04)
12. `resounding.tags.json` / `resounding.materials.json` are read at `Cache.java:44-45` but **have never
    existed in this repo's history**. This is why the system is half-finished: it has never had input.

### E. Verification blocked (S-03)
`verify.yml` pins JDK 17 against `release = 21`; the CodeQL `matrix.java == '21'` condition is
**unreachable**; the workflow triggers only on `master`, so **the working branch has no CI**.
Correction carried from review 1: the pipeline is *not* uniformly MC-coupled — `Property`, `RawTag`,
`Tag`, `RawMaterial`, `Material` import nothing external; `TagRegistry`'s only non-JDK import is
`LinkedTreeMap`. This is a **move + fix + rename**, not construction of a parallel system.

### F. Found in review
13. **`TagRegistry.tags` is write-only** — sole reference is `Cache.java:66`; the registry scan never
    appends the scanned block to `RawTag.blocks`, so registry tags resolve to **empty** `Tag`s.
    **Contract:** `TagResolver` returns forward map **and** reverse index in one immutable `Resolution`.
    *Justification (corrected): the forward map's consumer today is the test suite and diagnostics.*
    Resolution necessarily computes tag→blocks as its intermediate; the reverse index is its inversion, so
    exposing both is **retention, not construction**, and it is the natural assertion surface.
14. **Null-state NPE / broken `@NotNull`** — `material(@Nullable BlockState)` dereferences `state` and can
    return null; `OctreeManager.growOctree:68,82-83` then NPEs **on pool threads**, where it is swallowed.
15. **Data race** — `Cache.generate()` runs from `WorldChunkMixin.initStorage:139` while octree threads read
    `MaterialRegistry.materials`, a bare `HashMap` mutated by `putAll` with no safe publication.
16. **Untrusted pack JSON crashes chunk load** — `Utils.java:141` lets `JsonSyntaxException` escape.
17. **Leaks** — `Utils.resource` never closes its stream; `Cache.java:43` never closes the `ResourcePack`.
18. **Shells clobber authored materials** — `Cache.java:69` `putAll(shellMaterials(...))` runs last.
    *Fix is a precedence rule, not an ordering tweak: generated shells rank **below all authored layers**.*
19. **Duplicate reverse-index entries** — `Utils.update` appends unconditionally, so a block matched by both
    a registry tag and a pattern **inflates that solute's blend weight**. `BlockIndex` is set-valued.

---

## 2. Architecture — replace, don't parallel

```
tag/       Ident · RawTagDef · BlockIndex · TagResolver · Resolution · Diagnostic
material/  RawMaterialDef · Blend · MaterialResolver
data/      JsonAdapter · DefaultData · LayeredSource     (the ONLY place Gson appears)
```
Pure Java: no Minecraft, Fabric, Gson, or commons in `tag/` and `material/`. Lane 1 compilation
*structurally enforces* this — an MC import there fails the build.

`Cache` / `TagRegistry` / `MaterialRegistry` survive **only as adapters**.

**Deletion gate (P6 acceptance, enforceable):**
1. `RawTag.java`, `RawMaterial.java`, `Tag.java`, `Property.java` **do not exist**;
2. zero references to those four names anywhere in `src/main/java`, **including `data/`**;
3. `LinkedTreeMap|com.google.gson` appears **only** under `data/`.

### Identifier: cold path vs hot path
- **Cold (generation):** `Ident` is a record over `namespace:path`, derived by the adapter from
  `Registries.BLOCK.getId(block)` — the domain vanilla tags, packs, and other mods already use.
- **Hot (runtime):** an **`IdentityHashMap<Block, Material>`, fully populated at bake, wrapped immutable,
  published by a single `volatile` reference swap.** No array, no sentinel slot (raw id 0 is a real block),
  and no "no hashing" claim — `getRawId` is itself a `Reference2IntOpenHashMap` lookup, so an array buys
  nothing over an identity map while carrying density/`ABSENT_RAW_ID`/freeze-order reasoning.
  Regeneration builds fresh and swaps; readers never see a torn map (defect 15).
- **Contract pinned in the skeleton:** `material(null) == DEFAULT`; never returns null; race-free against
  regeneration.
- **The core never sees a block id.** The adapter derives `Ident` from **`BlockState`**, so per-state
  acoustics later is an adapter change. Fluid tags (`Registries.FLUID`, invisible to
  `BlockState.streamTags()`) are a **recorded residue**, not a stub API.

---

## 3. Layering and merge semantics *(the gap that made review 2 say NOT READY)*

**Layer order, lowest to highest:** generated shells → container defaults → enabled packs in
resource-manager application order. **A generated shell never replaces an authored material** — this, not
ordering, is the fix for defect 18.

**Tags — vanilla datapack semantics.** Same id across layers ⇒ **union** of the four arrays. Optional
`"replace": true` (default false) discards lower layers for that tag and emits a `Shadowed` diagnostic.
This is Minecraft's own model, so pack authors already know it, and it serves the documented
"expand an existing tag in a modpack" intent.

**Materials — per-field overlay.** Every field is optional by design, so per name a higher layer's non-null
field wins and nulls inherit downward: a pack retunes `density` on `stone` in three lines. Whole-record
replace is rejected — it forces restating every field, and solute-referencing the shadowed default would be
a same-name cycle. **Edge rule: `solute` and `composition` replace atomically as a pair** (mixing layers
reintroduces defect 8).

All five behaviours are pinned by P1b tests.

---

## 4. Test harness

**Lane 1 — `core-tests/`, standalone Gradle build.** Own `settings.gradle`, deliberately not included from
root, so Loom never configures it. Gradle's settings search stops at the start directory, so
`-p core-tests` makes it the build root with no walk-up. Own wrapper pinned to **8.8** (cached). Mounts
`srcDir '../src/main/java'` filtered to `**/tag/**`, `**/material/**`, `**/data/**`, plus the shared
`../src/test/java`. Deps: JUnit 5, Gson, annotations. `JAVA_HOME` exported explicitly — no toolchains.
No duplicate-class risk: separate builds each compile every file once.

**Lane 2 — root build.** `test` source set + JUnit 5 running the *same* test sources. Verified working.

**P0 canary:** a task asserting a nonzero core class count, so an include-filter typo is loud at P0 rather
than silent at P1b.

**Fixture:** `FakeBlockRegistry` — plain records (id, tag ids). No state variants; nothing this pass needs
them.

**CI:** JDK 21, fix the unreachable CodeQL condition, add the working branch to triggers.

---

## 5. Execution

- **P0 (me).** Commit baseline. Both lanes + canary green.
- **P1a (me).** Compilable API skeleton — every type and signature, javadoc'd contracts, `UnsupportedOperationException`
  bodies — plus `FakeBlockRegistry`. Stays with the parent: skeleton errors multiply across five downstream
  phases, so this is where the expensive model is cheapest per token.
- **P1b (sonnet).** All-red suite **against the skeleton**. Gate: *compiles cleanly, fails on UOE or
  assertion* — never on a missing symbol. Covers 3,4,5,6,7,8,9,11,12,13,14,16,19 + the five merge rules + e2e.
- **P2 (sonnet).** `Ident`, `BlockIndex`, `Diagnostic`, `Resolution`.
- **P3 (sonnet — swapped).** `TagResolver`. The hardest phase (recursion, cycle diagnostics, layered merge)
  gets the deterministic model, not `--model auto`.
- **P4 (cursor auto — swapped).** `Blend` + `MaterialResolver`: test-pinned arithmetic, good cursor material.
- **P5 (cursor auto for the adapter half; sonnet + parent plausibility gate on the physical values).**
  `JsonAdapter`, `DefaultData`, `LayeredSource`, and the two resource files.
- **P6 (me + sonnet).** Adapters; identity-map hot path (1,14); volatile publication (15); close packs and
  streams (17); shell-lowest precedence (18); explicit `TypeToken` subclasses (10); **deletion gate clean.**
- **P7 (me).** Both lanes green, lints, scope check, `residual verify`, residues recorded.

---

## 6. Scope

- **"These components" = the tag/material pipeline.** The harness is component-agnostic so the octree layer
  can drop in later, but `OctreeManager`/`Branch`/`Cast` need `WorldChunk`/`VoxelShape` fakes and are
  **explicitly deferred**.
- **"Working again" = both lanes green + `./gradlew build` succeeds.** No in-game launch; not asked for,
  and it will be stated plainly rather than implied.
- **Untouched:** raycast/OpenAL/effects/config-GUI behaviour, the missing `Plugin` class, and the broader
  architecture the user deferred.

## 7. Cuts (over-corrections removed)

`Acoustics` class (two one-liners → private statics) · `fluidTagsFor` no-op seam (residue note instead) ·
Gradle toolchains (non-functional here) · `SoundClassifier` as a named deliverable (keep only if free) ·
`FakeBlockRegistry` state variants (speculative) · `ResolvedTag` if it is only a `Set<Ident>` wrapper.

## 8. Residual risk

Yarn signatures are now **verified**, not assumed. Remaining: Fabric's mod-jar-as-resource-pack behaviour is
impl-internal (mitigated by container-first loading), and the default JSON physical values are a judgement
call — isolated as data precisely so they are cheap to replace.
