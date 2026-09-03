# Octree plant debounce and chunk-gen observation

## Status

- Debounce (section 2) shipped.
- Chunk-generation observation (section 3) shipped: custom `ChunkGenEvents` sink + S2C bridge.

## 2. Debounce with in-ring priority

**Ring.** Chebyshev: `max(|dx|,|dz|) <= soundSimulationDistance + 2`.

| Lane | When | 2s quiet |
|------|------|----------|
| Priority | In-ring and not near generation | Ignored |
| Deferred | Out-of-ring, or near generation | Required for out-of-ring; gen-adjacent waits until clear |

In-ring loads jump the priority front. Deferred promotes when the player walks near and generation is quiet. See `OctreePlantScheduler`.

## 3. Chunk generation observation (implemented)

Fabric API `0.100.4+1.21` has no `ServerChunkEvents.CHUNK_GENERATE`. Yarn 1.21+build.7 also leaves the `convertToFullChunk` supplyAsync lambda unmapped (`method_60553`).

**Sink.** [`ChunkGenEvents.CHUNK_GENERATE`](src/main/java/dev/thedocruby/resounding/event/ChunkGenEvents.java) + mixin [`ChunkGeneratingMixin`](src/main/java/dev/thedocruby/resounding/mixin/server/ChunkGeneratingMixin.java) targeting `method_60553` at TAIL, same guard as upstream Fabric: skip `WrapperProtoChunk` (disk reload), fire for fresh proto→full.

**Activity.** [`ChunkGenerationActivity`](src/main/java/dev/thedocruby/resounding/ChunkGenerationActivity.java) records generated positions for 2s; Chebyshev radius 1 counts as “near generation.”

**Bridge.** [`Mod`](src/main/java/dev/thedocruby/resounding/Mod.java) registers the event (notes activity + S2C [`ChunkGeneratedPayload`](src/main/java/dev/thedocruby/resounding/network/ChunkGeneratedPayload.java) to world players). Client receiver notes the same map so dedicated-server gen reaches client planting.

**Scheduler.** In-ring + near-gen → deferred; priority demotes if gen appears underfoot; deferred drain skips still-hot gen neighbors.
