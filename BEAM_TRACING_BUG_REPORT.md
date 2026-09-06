# Beam-tracing frustum bug report — post-A-E capture session

**Resolution**: all 5 fixes (owner-directed, overruling parts of this
document's own investigation findings B and C where the owner had first-hand
context this read-only investigation didn't) are specified and implemented
as **Task F** in `BEAM_TRACING_TDD_PLAN.md` — see that doc for the final
authoritative fix specs and implementation status. This document's findings
below are preserved as the investigation record; where Task F's spec
disagrees with a conclusion here (Investigation B's "wouldDoubleCover has no
gap" and Investigation C's "not a bounds bug"), Task F's owner-directed
framing is what was actually implemented.

Branch: `beam-tracing-frustums`, HEAD `cd184c4` (Task E: patient frustum growth)
plus uncommitted debug UX changes to `CaptureBuffer`/`DebugKeybinds`/`DebugPicker`
(deferred-clear capture buffer, chat+log status readouts) that were used to take
this capture. Captured via `C` (arm) → sound → `K` (kapture dump) per ray, logged
to `run/logs/latest.log`. Raw data preserved below so this doesn't depend on the
log file surviving.

The owner captured 4 rays and found defects the approved A–E design says should
not occur:

1. **Red markers between air:air boundaries** (1 ray implicated).
2. **Frustum grows when it should reflect/defer on the next same-size segment**
   — "need to check both same size and grown size when about to grow" (2 rays).
3. **A larger octant envelops a smaller one** — "trivially verifiable with
   arithmetic" (2 rays).

Only 4 rays were captured (#50, #62, #63, #42), so at least one ray exhibits more
than one symptom. Ray #62 is the only one of the four with **no** anomalies
(constant `Z=426.9`, `polar=1.000` throughout, monotonic frustum growth, clean
termination) — likely the control / "how it should look" example, not one of the
bug exhibits.

## Shared suspicious pattern (rays #50, #63, #42)

All three show `material=air` segments where `Z` (resolved/blended impedance)
spikes into the tens of millions — e.g. ray #50 hits `Z=13135521.8` and
`Z=2168103.7` on `air` cells while the ray is nowhere near bedrock (bedrock
first appears ~10 blocks later in the same ray); ray #63 hits `Z=32676640.0`;
ray #42 hits `Z=28161722.7`. **These values exceed even bedrock's own impedance
(`4821547.5`, confirmed elsewhere in the same rays)** — i.e. the blended value
is not "air blended toward bedrock," it's higher than any real material
involved. This smells like a bake/aggregation defect upstream of the blend
formula (`Physics.blendImpedance`, `Cast.polarizedImpedance`), not the blend
formula itself (which is just a linear interpolation and can't exceed its
inputs' range if the inputs are sane).

`OctreeLayer.java:814` colors quartet-interaction crosses **red for
"reflection," green for "transmission," yellow for "split"** — this is almost
certainly the source of symptom (1)'s red markers on air:air, if that
classifier (or its impedance-contrast reasoning) is fed the same corrupted
blended-Z values. Real `Cast.raycast()` physics reports `R=0.000` throughout
every line of every captured ray (never actually reflects) — so the red marker
would be a **visualization-only false positive** riding on the same corrupted
descriptor data, not an actual physics divergence... unless investigation shows
otherwise.

## Task E symptom: growth never defers/reflects

Every one of the 4 rays stays at `bounce #0` for its entire lifetime — none of
them ever executes an actual reflect. `frustum=` grows monotonically call after
call (e.g. ray #50: 0.78→0.91→1.38→1.76→...→19.63; ray #42 grows all the way to
22.19 before terminating inside bedrock). Task E's `lastGrowthDeferred`/
`lastFreeRefraction` (`Cast.java:99,105`, set at `Cast.java:395-397` via
`FrustumLod.wouldDoubleCover`, `FrustumLod.java:140-163`) never appear to have
fired in any of these traces (no distinguishing marker in the current
`KaptureLogger` format — worth checking if that's a logging gap or the flag
truly never sets). The owner's framing: **"need to check both same size and
grown size when about to grow"** — current `wouldDoubleCover` early-returns
`false` whenever `candidateSize <= currentSize` (`FrustumLod.java` ~154-156),
which only ever runs the clearance check against the *grown* candidate size,
never against the *current* (already-established) size on a step where growth
is attempted. If the current footprint already double-covers past the polarity
plane, growth is presumably still allowed as long as the arithmetic comparison
happens to fall the wrong way — this needs source-level verification, not just
inference from the log.

## Raw KAPTURE dumps (verbatim from `run/logs/latest.log`)

### Ray #50 — `run/logs/latest.log:8825-8880`
```
Resounding KAPTURE ray #50 sound=1 bounces=26 ---
node=1³ mode=VOXEL start=-297.5000,-22.5000,568.5000 end=-296.9999,-22.8552,568.1064 material=air Zprev=426.9 Z=426.9 R=0.000 T=1.000 power=128.0 polar=1.000 frustum=0.78
node=1³ mode=VOXEL start=-296.9999,-22.8552,568.1064 end=-296.8648,-22.9513,568.0000 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.999 power=127.9 polar=1.000 frustum=0.87
node=1³ mode=VOXEL start=-296.8648,-22.9513,568.0000 end=-296.7961,-23.0000,567.9568 material=air Zprev=426.9 Z=426.9 R=0.000 T=1.000 power=127.9 polar=1.000 frustum=0.91
node=1³ mode=VOXEL start=-296.7961,-23.0000,567.9568 end=-296.0000,-23.4525,567.4554 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.996 power=127.4 polar=1.000 frustum=1.38
node=1³ mode=VOXEL start=-296.0000,-23.4525,567.4554 end=-295.4210,-23.8634,567.0000 material=air lod1 Zprev=426.9 Z=426.9 R=0.000 T=0.997 power=127.0 polar=1.000 frustum=1.76
node=1³ mode=VOXEL start=-295.4210,-23.8634,567.0000 end=-295.2285,-24.0000,566.8788 material=air lod1 Zprev=426.9 Z=426.9 R=0.000 T=0.999 power=126.8 polar=1.000 frustum=1.88
node=1³ mode=VOXEL start=-295.2285,-24.0000,566.8788 end=-295.0000,-24.1298,566.7349 material=air lod1 Zprev=426.9 Z=426.9 R=0.000 T=0.999 power=126.7 polar=1.000 frustum=2.02
node=1³ mode=VOXEL start=-295.0000,-24.1298,566.7349 end=-294.0660,-24.7926,566.0000 material=air lod1 Zprev=426.9 Z=426.9 R=0.000 T=0.995 power=126.1 polar=1.000 frustum=2.63
node=1³ mode=VOXEL start=-294.0660,-24.7926,566.0000 end=-294.0000,-24.8395,565.9584 material=air lod1 Zprev=426.9 Z=426.9 R=0.000 T=1.000 power=126.0 polar=1.000 frustum=2.67
node=1³ mode=VOXEL start=-294.0000,-24.8395,565.9584 end=-293.8191,-25.0000,565.8158 material=air lod1 Zprev=426.9 Z=426.9 R=0.000 T=0.999 power=125.9 polar=1.000 frustum=2.80
node=1³ mode=VOXEL start=-293.8191,-25.0000,565.8158 end=-293.0000,-25.5814,565.1706 material=air lod1 Zprev=426.9 Z=426.9 R=0.000 T=0.996 power=125.3 polar=1.000 frustum=3.33
node=2³ mode=VOXEL start=-293.0000,-25.5814,565.1706 end=-292.5277,-26.0000,564.7060 material=air lod2 Zprev=426.9 Z=426.9 R=0.000 T=0.997 power=125.0 polar=1.000 frustum=3.69
node=2³ mode=VOXEL start=-292.5277,-26.0000,564.7060 end=-292.0000,-26.3744,564.1869 material=air lod2 Zprev=426.9 Z=426.9 R=0.000 T=0.997 power=124.6 polar=1.000 frustum=4.06
node=2³ mode=VOXEL start=-292.0000,-26.3744,564.1869 end=-291.8479,-26.5093,564.0000 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.999 power=124.4 polar=1.000 frustum=4.19
node=2³ mode=VOXEL start=-291.8479,-26.5093,564.0000 end=-290.1658,-28.0000,562.3463 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.990 power=123.2 polar=1.000 frustum=5.44
node=4³ mode=VOXEL start=-290.1658,-28.0000,562.3463 end=-288.0000,-29.5396,560.2170 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.987 power=121.6 polar=1.000 frustum=6.98
node=4³ mode=VOXEL start=-288.0000,-29.5396,560.2170 end=-287.8229,-29.6964,560.0000 material=air Zprev=426.9 Z=13135521.8 R=0.000 T=0.999 power=121.5 polar=0.132 frustum=6.98
node=4³ mode=VOXEL start=-287.8229,-29.6964,560.0000 end=-288.0000,-29.9220,559.7504 material=air Zprev=426.9 Z=609629.6 R=0.000 T=0.999 power=121.3 polar=0.058 frustum=6.98
node=4³ mode=VOXEL start=-288.0000,-29.9220,559.7504 end=-289.0226,-32.0000,557.4504 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.988 power=119.8 polar=1.000 frustum=8.45
node=8³ mode=VOXEL start=-289.0226,-32.0000,557.4504 end=-291.4459,-35.9515,552.0000 material=air Zprev=426.9 Z=715937.9 R=0.000 T=0.974 power=116.7 polar=0.236 frustum=8.45
node=8³ mode=VOXEL start=-291.4459,-35.9515,552.0000 end=-293.9286,-40.0000,550.1127 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.981 power=114.5 polar=1.000 frustum=10.75
node=16³ mode=VOXEL start=-293.9286,-40.0000,550.1127 end=-300.0324,-48.0000,545.4727 material=air Zprev=426.9 Z=2168103.7 R=0.000 T=0.960 power=109.8 polar=0.748 frustum=10.75
node=16³ mode=VOXEL start=-300.0324,-48.0000,545.4727 end=-304.0000,-53.0215,544.2742 material=bedrock Zprev=426.9 Z=4821547.5 R=0.000 T=0.976 power=107.2 polar=1.000 frustum=13.69
node=16³ mode=VOXEL start=-304.0000,-53.0215,544.2742 end=-304.7305,-54.1704,544.0000 material=bedrock Zprev=426.9 Z=4821547.5 R=0.000 T=0.995 power=106.6 polar=1.000 frustum=14.31
node=16³ mode=VOXEL start=-304.7305,-54.1704,544.0000 end=-310.9810,-64.0000,542.1207 material=bedrock Zprev=426.9 Z=4821547.5 R=0.000 T=0.957 power=102.1 polar=1.000 frustum=19.63
node=16³ mode=VOXEL start=-310.9810,-64.0000,542.1207 end=-310.9810,-64.0000,542.1207 material=bedrock Zprev=426.9 Z=4821547.5 R=0.000 T=0.000 power=102.1 polar=1.000 frustum=19.63 TERMINATED
Resounding KAPTURE ray #50 --- end
```

### Ray #62 — `run/logs/latest.log:9959-10007` (clean control, no anomalies)
```
Resounding KAPTURE ray #62 sound=1 bounces=23 ---
node=1³ ... material=air Zprev=426.9 Z=426.9 ... polar=1.000 frustum=0.70
[... all segments material=air, Z constant at 426.9, polar=1.000, frustum grows monotonically 0.70 -> 42.07, single continuous VOXEL walk through air, no bedrock/solid encountered ...]
node=16³ mode=VOXEL start=-328.1985,18.4178,512.0000 end=-336.0000,27.3573,501.6040 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.943 power=90.9 polar=1.000 frustum=42.07 TERMINATED
Resounding KAPTURE ray #62 --- end
```
(Full text available in `run/logs/latest.log:9959-10007`; every line has
`Z=426.9`, `R=0.000`, `polar=1.000` — no spikes, unlike #50/#63/#42.)

### Ray #63 — `run/logs/latest.log:10687-10753`
```
Resounding KAPTURE ray #63 sound=1 bounces=32 ---
node=1³ ... material=air Zprev=426.9 Z=426.9 ... polar=1.000 frustum=0.69
node=1³ ... material=air Zprev=426.9 Z=426.9 ... polar=1.000 frustum=1.12
node=1³ ... material=air Zprev=426.9 Z=426.9 ... polar=1.000 frustum=1.18
node=1³ ... material=air Zprev=426.9 Z=426.9 ... polar=1.000 frustum=1.66
node=1³ ... material=air Zprev=426.9 Z=426.9 ... polar=1.000 frustum=1.82
node=1³ ... material=air Zprev=426.9 Z=426.9 ... polar=1.000 frustum=2.17
node=1³ ... material=air Zprev=426.9 Z=426.9 ... polar=1.000 frustum=2.30
node=1³ ... material=air lod1 Zprev=426.9 Z=426.9 ... polar=1.000 frustum=2.68
node=1³ ... material=air Zprev=426.9 Z=426.9 ... polar=1.000 frustum=3.22
node=2³ mode=VOXEL start=-295.0951,-23.6732,563.0000 end=-294.4782,-24.0000,562.1291 material=wool Zprev=426.9 Z=12374.8 R=0.000 T=0.435 power=54.6 polar=1.000 frustum=3.72
node=2³ mode=VOXEL start=-294.4782,-24.0000,562.1291 end=-294.3868,-24.0442,562.0000 material=air Zprev=41471.0 Z=426.9 R=0.000 T=0.998 power=54.4 polar=0.951 frustum=3.72
node=2³ mode=VOXEL start=-294.3868,-24.0442,562.0000 end=-294.0000,-24.2178,561.5629 material=air Zprev=32676640.0 Z=11825440.7 R=0.000 T=0.991 power=53.9 polar=0.348 frustum=3.72
node=2³ mode=VOXEL start=-294.0000,-24.2178,561.5629 end=-292.8913,-24.7878,560.0000 material=air Zprev=32676640.0 Z=16464028.4 R=0.000 T=0.971 power=52.3 polar=0.480 frustum=3.72
node=2³ mode=VOXEL start=-292.8913,-24.7878,560.0000 end=-292.0000,-25.2088,558.9876 material=air Zprev=32676640.0 Z=457264.0 R=0.000 T=0.979 power=51.3 polar=0.008 frustum=3.72
node=2³ mode=VOXEL start=-292.0000,-25.2088,558.9876 end=-291.3008,-25.5830,558.0000 material=air Zprev=426.9 Z=1757420.1 R=0.000 T=0.995 power=51.0 polar=0.066 frustum=3.72
node=2³ mode=VOXEL start=-291.3008,-25.5830,558.0000 end=-290.4472,-26.0000,557.0343 material=air Zprev=426.9 Z=11740242.4 R=0.000 T=0.995 power=50.8 polar=0.376 frustum=3.72
node=2³ mode=VOXEL start=-290.4472,-26.0000,557.0343 end=-290.0000,-26.1584,556.5283 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.997 power=50.6 polar=1.000 frustum=4.03
node=2³ mode=VOXEL start=-290.0000,-26.1584,556.5283 end=-289.6262,-26.3238,556.0000 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.998 power=50.5 polar=1.000 frustum=4.33
node=2³ mode=VOXEL start=-289.6262,-26.3238,556.0000 end=-288.0000,-27.0434,554.1602 material=air Zprev=426.9 Z=2258662.4 R=0.000 T=0.963 power=48.6 polar=0.257 frustum=4.33
node=2³ mode=VOXEL start=-288.0000,-27.0434,554.1602 end=-287.7633,-27.1731,554.0000 material=air Zprev=426.9 Z=1261368.0 R=0.000 T=0.999 power=48.6 polar=0.067 frustum=4.33
node=2³ mode=VOXEL start=-287.7633,-27.1731,554.0000 end=-286.2543,-28.0000,553.3957 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.993 power=48.2 polar=1.000 frustum=5.15
node=4³ mode=VOXEL start=-286.2543,-28.0000,553.3957 end=-284.0000,-28.9899,552.4929 material=air Zprev=426.9 Z=1196601.3 R=0.000 T=0.990 power=47.8 polar=0.096 frustum=5.15
node=4³ mode=VOXEL start=-284.0000,-28.9899,552.4929 end=-282.8804,-29.6030,552.0000 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.995 power=47.5 polar=1.000 frustum=5.77
node=4³ mode=VOXEL start=-282.8804,-29.6030,552.0000 end=-280.0000,-31.1803,550.9843 material=air lod4 Zprev=426.9 Z=426.9 R=0.000 T=0.987 power=46.9 polar=1.000 frustum=7.32
node=8³ mode=VOXEL start=-280.0000,-31.1803,550.9843 end=-278.7986,-32.0000,550.4565 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.994 power=46.6 polar=1.000 frustum=8.02
node=8³ mode=VOXEL start=-278.7986,-32.0000,550.4565 end=-272.0000,-35.7163,547.4694 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.970 power=45.2 polar=1.000 frustum=11.76
node=16³ mode=VOXEL start=-272.0000,-35.7163,547.4694 end=-265.6348,-40.0327,544.0000 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.969 power=43.8 polar=1.000 frustum=15.56
node=16³ mode=VOXEL start=-265.6348,-40.0327,544.0000 end=-256.0000,-46.5662,539.7663 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.955 power=41.8 polar=1.000 frustum=21.14
node=16³ mode=VOXEL start=-256.0000,-46.5662,539.7663 end=-254.2894,-48.0000,538.8372 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.991 power=41.5 polar=1.000 frustum=22.23
node=16³ mode=VOXEL start=-254.2894,-48.0000,538.8372 end=-240.0000,-57.6034,531.0759 material=bedrock Zprev=426.9 Z=4821547.5 R=0.000 T=0.932 power=38.6 polar=1.000 frustum=30.74
node=16³ mode=VOXEL start=-240.0000,-57.6034,531.0759 end=-235.3925,-61.4093,528.0000 material=bedrock Zprev=426.9 Z=4994888.0 R=0.000 T=0.975 power=37.7 polar=0.527 frustum=30.74
node=16³ mode=VOXEL start=-235.3925,-61.4093,528.0000 end=-232.2563,-64.0000,526.3146 material=bedrock Zprev=426.9 Z=4821547.5 R=0.000 T=0.579 power=21.8 polar=1.000 frustum=32.73 TERMINATED
Resounding KAPTURE ray #63 --- end
```

### Ray #42 — `run/logs/latest.log:11511-11579`
```
Resounding KAPTURE ray #42 sound=1 bounces=33 ---
node=1³ ... material=air Zprev=426.9 Z=426.9 ... polar=1.000 frustum=0.70
node=1³ ... material=air Zprev=426.9 Z=426.9 ... polar=1.000 frustum=1.19
node=1³ ... material=air Zprev=426.9 Z=426.9 ... polar=1.000 frustum=1.20
node=1³ ... material=air lod1 Zprev=426.9 Z=426.9 ... polar=1.000 frustum=1.32
node=1³ ... material=air lod1 Zprev=426.9 Z=426.9 ... polar=1.000 frustum=1.68
node=1³ ... material=air lod1 Zprev=426.9 Z=426.9 ... polar=1.000 frustum=2.19
node=1³ ... material=air lod1 Zprev=426.9 Z=426.9 ... polar=1.000 frustum=2.48
node=1³ ... material=air lod1 Zprev=426.9 Z=426.9 ... polar=1.000 frustum=2.72
node=1³ ... material=air lod1 Zprev=426.9 Z=426.9 ... polar=1.000 frustum=2.75
node=1³ ... material=air lod1 Zprev=426.9 Z=426.9 ... polar=1.000 frustum=3.26
node=2³ mode=VOXEL start=-292.0000,-24.4044,566.3059 end=-291.5179,-24.6614,566.0000 material=air Zprev=426.9 Z=7649345.1 R=0.000 T=0.991 power=124.1 polar=0.199 frustum=3.26
node=2³ mode=VOXEL start=-291.5179,-24.6614,566.0000 end=-290.0000,-25.3343,565.2276 material=air Zprev=32676640.0 Z=262127.7 R=0.000 T=0.973 power=120.8 polar=0.000 frustum=3.26
node=2³ mode=VOXEL start=-290.0000,-25.3343,565.2276 end=-288.6017,-26.0000,564.3442 material=air Zprev=426.9 Z=14544654.8 R=0.000 T=0.993 power=120.0 polar=0.482 frustum=3.26
node=2³ mode=VOXEL start=-288.6017,-26.0000,564.3442 end=-288.0570,-26.1789,564.0000 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.998 power=119.7 polar=1.000 frustum=3.56
node=2³ mode=VOXEL start=-288.0570,-26.1789,564.0000 end=-288.0000,-26.1976,563.9712 material=air Zprev=426.9 Z=426.9 R=0.000 T=1.000 power=119.7 polar=1.000 frustum=3.59
node=2³ mode=VOXEL start=-288.0000,-26.1976,563.9712 end=-286.0000,-27.0183,562.7069 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.991 power=118.6 polar=1.000 frustum=4.72
node=2³ mode=VOXEL start=-286.0000,-27.0183,562.7069 end=-285.1034,-27.4772,562.0000 material=air Zprev=426.9 Z=28161722.7 R=0.000 T=0.995 power=118.0 polar=0.831 frustum=4.72
node=2³ mode=VOXEL start=-285.1034,-27.4772,562.0000 end=-285.5826,-28.0000,561.3550 material=air Zprev=426.9 Z=11593.2 R=0.000 T=0.996 power=117.6 polar=0.007 frustum=4.72
node=2³ mode=VOXEL start=-285.5826,-28.0000,561.3550 end=-286.0000,-28.4862,560.6059 material=air Zprev=426.9 Z=4664932.2 R=0.000 T=0.996 power=117.2 polar=0.176 frustum=4.72
node=2³ mode=VOXEL start=-286.0000,-28.4862,560.6059 end=-286.2219,-28.8794,560.0000 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.997 power=116.8 polar=1.000 frustum=5.06
node=4³ mode=VOXEL start=-286.2219,-28.8794,560.0000 end=-287.9827,-32.0000,556.1506 material=air Zprev=426.9 Z=10743616.7 R=0.000 T=0.981 power=114.6 polar=0.692 frustum=5.06
node=4³ mode=VOXEL start=-287.9827,-32.0000,556.1506 end=-288.0000,-32.0278,556.1080 material=air Zprev=426.9 Z=881964.5 R=0.000 T=1.000 power=114.5 polar=0.065 frustum=5.06
node=4³ mode=VOXEL start=-288.0000,-32.0278,556.1080 end=-288.0315,-32.0981,556.0000 material=air Zprev=426.9 Z=426.9 R=0.000 T=1.000 power=114.5 polar=1.000 frustum=5.12
node=4³ mode=VOXEL start=-288.0315,-32.0981,556.0000 end=-289.4874,-35.3562,552.0000 material=air Zprev=426.9 Z=277787.3 R=0.000 T=0.923 power=105.7 polar=0.249 frustum=5.12
node=4³ mode=VOXEL start=-289.4874,-35.3562,552.0000 end=-289.7751,-36.0000,551.7144 material=air lod4 Zprev=426.9 Z=426.9 R=0.000 T=0.997 power=105.4 polar=1.000 frustum=5.47
node=4³ mode=VOXEL start=-289.7751,-36.0000,551.7144 end=-292.0000,-39.9860,549.5055 material=air lod4 Zprev=426.9 Z=426.9 R=0.000 T=0.981 power=103.4 polar=1.000 frustum=7.75
node=8³ mode=VOXEL start=-292.0000,-39.9860,549.5055 end=-292.0063,-40.0000,549.4978 material=air Zprev=426.9 Z=426.9 R=0.000 T=1.000 power=103.4 polar=1.000 frustum=7.76
node=8³ mode=VOXEL start=-292.0063,-40.0000,549.4978 end=-296.0000,-47.1216,544.5647 material=air Zprev=426.9 Z=426.9 R=0.000 T=0.965 power=99.8 polar=1.000 frustum=12.06
node=16³ mode=VOXEL start=-296.0000,-47.1216,544.5647 end=-296.3690,-47.9369,544.0000 material=air Zprev=426.9 Z=1200213.2 R=0.000 T=0.996 power=99.4 polar=0.909 frustum=12.53
node=16³ mode=VOXEL start=-296.3690,-47.9369,544.0000 end=-296.3975,-48.0000,544.0350 material=air Zprev=426.9 Z=3692412.2 R=0.000 T=1.000 power=99.4 polar=0.880 frustum=12.56
node=16³ mode=VOXEL start=-296.3975,-48.0000,544.0350 end=-304.0000,-61.4403,553.3535 material=bedrock Zprev=426.9 Z=4821547.5 R=0.000 T=0.935 power=92.9 polar=1.000 frustum=20.69
node=16³ mode=VOXEL start=-304.0000,-61.4403,553.3535 end=-305.1771,-64.0000,555.1281 material=bedrock Zprev=426.9 Z=4821547.5 R=0.000 T=0.661 power=61.4 polar=1.000 frustum=22.19
node=16³ mode=VOXEL start=-305.1771,-64.0000,555.1281 end=-305.1771,-64.0000,555.1281 material=bedrock Zprev=426.9 Z=4821547.5 R=0.000 T=0.000 power=61.4 polar=1.000 frustum=22.19 TERMINATED
Resounding KAPTURE ray #42 --- end
```

## Investigation assignments (subagents dispatched)

Three parallel investigation-only subagents were dispatched (no code changes,
findings only) against this data:

- **Investigation A — air:air red markers**: `OctreeLayer.java`'s
  quartet-interaction cross classifier (green=transmission/red=reflection/
  yellow=split, ~line 814) plus `Cast.polarizedImpedance`/
  `Physics.blendImpedance`/the `NodeDescriptor` bake path — why do air-labeled
  cells resolve `Z` values (tens of millions) that exceed even bedrock's own
  impedance (4,821,547.5), and does the overlay classifier get fooled by this
  into painting red on a boundary the real `Cast.raycast()` physics scores
  `R=0.000`?
- **Investigation B — growth doesn't defer/reflect**: `FrustumLod.wouldDoubleCover`
  (`FrustumLod.java` ~140-163) and its Cast wiring (`Cast.java:392-402`) — does
  the `candidateSize <= currentSize -> false` early-out skip checking
  double-cover against the *current* footprint size when growth is attempted,
  per the owner's "check both same size and grown size" framing? Why does
  `lastGrowthDeferred`/`lastFreeRefraction` never appear to fire across all 4
  captured rays even when grazing bedrock at a shallow angle?
- **Investigation C — larger octant envelops smaller one**: `Branch.java`
  (`getAtLod`, `virtualLodCell`, `neighbor`) and `OctreeManager.java`
  (`sameAcousticCell` and the bake/aggregation path) — verify with concrete
  arithmetic against the ray positions above whether a coarse node's resolved
  bounds/descriptor can incorrectly span/absorb a disjoint smaller octant's
  data (candidate root cause for both A and B above, since corrupted
  aggregate stats would explain both the runaway Z values and possibly
  corrupted/null polarity vectors feeding `wouldDoubleCover`).

Findings to be appended below once each subagent reports back.

---

## Findings

### Investigation A — air:air red markers — CONFIRMED, independent bug

**Root cause chain**: the debug overlay's quartet-interaction classifier
(`OctreeLayer.collectCastVisitedOverlay`,
`src/main/java/dev/thedocruby/resounding/debug/OctreeLayer.java:342-387`) does
**not** consult the real physics `R` value at all. It infers "was this a
reflect?" purely from a geometric direction-change test between consecutive
logged path segments (`isDirectionChange`, `OctreeLayer.java:397`,
`dot < 1.0 - 1e-4` — trips on almost any bend):
- If `prevBranchSize <= 1`: hardcodes `NedMarkerState.REFLECTION`
  unconditionally (`OctreeLayer.java:375-376`), no impedance check at all.
- If `prevBranchSize > 1`: calls `recordBounceOff`
  (`OctreeLayer.java:480-545`); when `FrustumLod.forwardMap(...)` returns
  `null` (no clean grid-face pairing), it **falls back to
  `NedMarkerState.REFLECTION`** unconditionally (`OctreeLayer.java:507-511`,
  comment: *"No forward survey possible on this axis pairing, but a direction
  change did happen"*).

This logic (commit `0844d51`, "Color-code ray death causes...", authored
before Task E existed) assumed the only way a ray's direction changes mid-path
is a real reflect. **Task E broke that assumption**: `Cast.java:392-402`
(`FrustumLod.wouldDoubleCover` → `Physics.grazeBend`) and `Cast.java:367-387`
(polarized notable-gate → `Physics.permeationBend`) both legitimately rotate
`vector` on the **transmit** path (`reflectivity` stays 0, correctly logged as
`R=0.000`) whenever `branchDescriptor.polar() != null`. The overlay sees the
resulting direction discontinuity and paints it red — a false positive. This
is why red markers co-locate with `R=0.000` log lines: the overlay classifier
and the real physics are reading different signals, not disagreeing about the
same one.

**Confidence**: High/confirmed via source + commit-history reading. Not yet
runtime-verified which of the two fallback lines (375-376 vs 507-511) fires
for the specific captured segments.

**Independent vs. shared root cause**: this bug is **independent** of the
Z-blend/aggregation defect below — the classifier's impedance path uses
`Branch.effectiveImpedance()` (`Branch.java:91-101`, prefers the safe
`avgImpedance` arithmetic mean) and never touches
`Cast.polarizedImpedance`/`Physics.blendImpedance` at all.

**Bonus lead for Investigation C** (flagged by this agent in passing): 
`Physics.blendImpedance` (`Physics.java:86-88`) is a true convex combination —
`w` is pre-clamped to `[0,1]` — so it cannot mathematically overshoot the
`[min,max]` of its inputs. But `Polarization.groupAdjust`
(`Polarization.java:80-92`), which **bakes** `mostCommonImpedance`/
`leastCommonImpedance` in the first place (called from
`OctreeManager.growOctree` → `bakeOctant`, `OctreeManager.java:222-237`), uses
coefficients `otherMean*(1-r²) + mostlyMean*√r` that do **not** sum to 1 for
`r∈(0,1)` (peak sum ≈1.47 at `r≈0.397`) — structurally capable of producing a
baked value exceeding both real inputs when they're comparable in magnitude.
`OctreeManager.bakeAggregateDescriptor` (`OctreeManager.java:269-316`) then
propagates that inflated value upward via plain unclamped arithmetic mean
across levels — a strong candidate for the runaway multi-million `Z` values
seen in the capture data.

**Recommended fix direction** (not yet applied): make the overlay classifier
consult `Cast`'s actual `lastFreeRefraction`/reflectivity state (or at minimum
exempt direction changes on cells where `polar() != null` and reflectivity was
0) instead of inferring reflect purely from geometric direction change.

### Investigation B — growth-instead-of-reflect — root cause is NOT what it looked like

**Confirmed structural facts**: `FrustumLod.wouldDoubleCover`
(`FrustumLod.java:140-165`) guards `polar==null → false` (144), then
`candidateSize<=currentSize → false` (147-149), then compares clearance to
`candidateSize/2`. Live call site `Cast.java:392-402` computes
`candidate = frustumSize + pConfig.frustumGrowthPerBlock * pdistance`.
`FrustumLod.growthPerBlock(int)` (`FrustumLod.java:58-63`) is strictly `>0` for
any finite ray count, and `pdistance` is a genuine positive segment length
— so **`candidate > frustumSize` holds on essentially every real call**.

**Key correction to the owner's framing**: the `candidateSize<=currentSize`
early-return is dead code in this wiring (it only matters for a hypothetical
reflect-leg caller that doesn't exist in `Cast.java` today). Testing
`candidate/2 > clearance` is mathematically a **superset** of testing
`current/2 > clearance` (bigger numerator, same clearance) — any case where
the *current* size alone would double-cover is already caught by the
candidate check. **Adding an explicit same-size check, as originally
suspected, would be a no-op — it would not fix anything.**

**The real gap (medium-high confidence)**: `branchDescriptor.polar()` is
likely null/unset at exactly the coarse LOD nodes where deferral should fire
— tying this directly to the same octree-aggregation defect surfacing as
corrupted impedances elsewhere in this log. Hand-checked ray #42's frustum
jump 12.56→20.69 (node=16³, exit y=-61.44 into bedrock): if that cell had a
real polar vector pointing toward bedrock, the exit position is already past
the mid-plane, so the "past-plane clamp" should have fired unconditionally —
it didn't. Can't be proven purely from the current log (see below) — would
need new instrumentation or a debugger breakpoint on `branchDescriptor.polar()`
at that node.

**Confirmed logging gap, and a red herring in the existing data**:
`Cast.lastGrowthDeferred`/`lastFreeRefraction` are not fields on
`CapturedRay`/`KaptureLogger` — no way to see if the flag ever fires from the
log alone. Worse: the logged `polar=` field (`Cast.lastPolarAlignment`)
**defaults to exactly `1.0`** whenever `branchDescriptor.polar()==null`
(`Cast.java:473-475`) — indistinguishable in the log from a genuine
full-alignment polarity hit. Every plain-air row reads `polar=1.000` for this
reason — this is why the capture data looked like "polar is present but
growth still happens," when actually `polar=1.000` may just mean "there is no
polarity here at all."

**Recommended fix direction** (not yet applied): don't touch the
`wouldDoubleCover` formula — it's fine. Add `lastGrowthDeferred`/
`lastFreeRefraction` and the raw polar null-ness to `CapturedRay`/
`KaptureLogger` to get ground truth on whether the predicate is even being
invoked with non-null polar at boundary nodes. If confirmed null there, the
fix belongs in the octree LOD aggregation path that computes
`NodeDescriptor.polar()`/`mostCommonImpedance`/`leastCommonImpedance` for
coarse nodes (same suspect as Investigation A's bonus lead: `Polarization
.groupAdjust` / `OctreeManager.bakeAggregateDescriptor`) — not in
`FrustumLod`/`Cast.raycast`'s Task E wiring, which appears correct as built.

### Investigation C — larger octant envelops smaller one — premise overturned: it's a labeling bug, not a bounds bug

**Verdict**: NOT a geometric bounds-overlap bug. The arithmetic in
`Branch.getAtLod`/`virtualLodCell`/`neighbor` checks out.

1. **Bounds arithmetic verified correct (high confidence).**
   `virtualLodCell` (`Branch.java:216-242`) is only reached when
   `lodSize < size`. Given that, `maxX = start.getX() + size - lodSize` is
   strictly `> start.getX()`, so the "double clamp" at `Branch.java:222-224`
   collapses to an ordinary single clamp — redundant, not buggy.
   `FrustumLod.alignOrigin` (`FrustumLod.java:205-211`) never actually fires
   its clamp for legitimately-routed calls because octree nodes are
   self-aligned (`start % size == 0`) and `lodSize` divides `size`. Hand-
   checked against ray #50's `node=4³` cell: origin stays inside the parent's
   own box, no overlap into a neighbor's space. `neighbor()`
   (`Branch.java:320-356`) only replicates a coarser ancestor's *scalar*
   material trivially — no aggregation, no compounding.

2. **The real "envelopment" is a labeling bug (medium-high confidence).**
   `Branch.getAtLod` deliberately returns a coarse node directly when
   `size==lod`, **even if it still has live, heterogeneous `children`**
   (`Branch.java:190-192` javadoc: "that node's baked polar/avg is the LOD
   aggregate"). Such a node has `material == null` (heterogeneous, set at
   `OctreeManager.java:246`) — but the debug label
   (`Branch.ensureMaterialLabel`, `Branch.java:291-301`) reads only
   `world.getBlockState(start)` — the octant's **corner block**, not the
   aggregate. So a genuinely heterogeneous 2³/4³ node (e.g. one denser block
   embedded in mostly-air) gets tagged `material=air` in the KAPTURE log
   purely because its corner voxel is air, while its baked
   `mostCommonImpedance`/`leastCommonImpedance` legitimately reflects the
   denser material elsewhere in that same small cube. Ray #63 shows an actual
   `material=wool` leaf immediately adjacent to the anomalous "air" segments —
   circumstantial confirmation this is a genuinely heterogeneous region, not
   corrupted data.

3. **`Polarization.groupAdjust` is a real, bounded arithmetic defect (medium
   confidence), but not the main story.** `g = otherMean*(1-r²) +
   mostlyMean*√r` (`Polarization.java:80-92`) has coefficients that don't sum
   to 1 for `r∈(0,1)` — peaks at `f(0.397)≈1.472`. Reachable integer ratios
   give `leastCommonImpedance` up to **~1.47×** overshoot of a real material
   mean — confirmed by hand arithmetic. But `OctreeManager
   .bakeAggregateDescriptor` only takes a plain unweighted mean across 8
   children (`OctreeManager.java:309-315`), which can't exceed its max input —
   so the ~1.47× overshoot doesn't compound through higher LOD levels. Caps
   around 1.47× max real material impedance — not enough alone to explain
   4.8M→32.6M.

4. **The "corruption" premise itself is unverified.** `MaterialResolver.java:
   26-27`: `MIN_IMPEDANCE=1e2`, `MAX_IMPEDANCE=1e8` (100 million) — the
   comment cites "dense stone ~1e7" as a *normal* value. Bedrock's 4.8M was
   never the registry's ceiling, just the highest value that happened to
   appear later in these 4 rays. Values of 11–32M are well inside the engine's
   designed range — before calling them corrupted, the actual baked
   impedances of whatever blocks occupy these coordinates should be checked
   against `MaterialRegistry`, not against bedrock.

**Recommended fix direction** (not yet applied): fix the label pipeline
first — when `branch.material == null` (heterogeneous aggregate), report that
explicitly in the KAPTURE dump/overlay instead of falling back to the single
corner-block label. This alone would likely eliminate the "material=air,
Z=millions" illusion, and probably the correlated red-marker false positives
too, since both likely key off the same mislabeled data. Separately, either
make `groupAdjust` a true convex blend (coefficients summing to 1) or
explicitly document/test its non-convex behavior as intentional. Add real
cell-origin bounds and the heterogeneous/`material==null` flag to the KAPTURE
log — the current format can't distinguish "genuine small-scale heterogeneous
cell" from "mislabeled coarse cell" or "corrupted data" without this, which is
why all three investigations below had to reason from code rather than
observe directly.

**Files/lines cited**: `Branch.java:190-242,320-356`;
`FrustumLod.java:205-211`; `OctreeManager.java:181-252,269-316`;
`Polarization.java:80-92,171-172`; `Cast.java:194-222,298-318,559-573`;
`MaterialResolver.java:25-33`; `Acoustics.java:41-43`.

---

## Synthesis — how the three findings fit together

The three symptoms turned out to be **more separate than the initial
hypothesis assumed**, and the "shared root cause" candidate
(`Polarization.groupAdjust`/`bakeAggregateDescriptor` aggregation corruption)
that Investigations A and B both flagged as a lead was **downgraded by
Investigation C**, which traced the same data and concluded the huge `Z`
values are most likely genuine (if surprising) physics on real heterogeneous
cells, mislabeled by a debug-only display bug — not corrupted aggregate
statistics. `groupAdjust`'s ~1.47× overshoot is real but too small to be the
whole story on its own.

Current best understanding per symptom:
1. **Red markers on air:air** — confirmed independent bug in
   `OctreeLayer`'s debug-overlay classifier (infers reflect from geometry,
   not real `R`); false positives on Task E's new legitimate transmit-path
   bends. Fix: overlay-only, in `OctreeLayer.java`.
2. **Growth doesn't defer/reflect** — the owner's originally suspected fix
   ("check same size and grown size") is a mathematical no-op in the current
   wiring; `Cast`/`FrustumLod`'s Task E implementation looks correct as
   built. Real suspect is `branchDescriptor.polar()` coming back null at
   boundary nodes, compounded by a logging blind spot (`polar=1.000` in the
   log is emitted both for "genuinely full alignment" and "no polarity at
   all" — indistinguishable without new instrumentation).
3. **Larger octant envelops smaller** — not a bounds bug; the octree
   geometry/arithmetic is sound. It's a debug-label bug
   (`ensureMaterialLabel` reports a heterogeneous aggregate's corner-block
   material as if the whole cell were homogeneous), which is the most likely
   explanation for why "material=air" cells show huge `Z`.

**Common thread**: all three investigations independently hit the same
diagnostic blind spot — the KAPTURE log/overlay cannot currently distinguish
"genuine small-scale heterogeneous cell," "mislabeled coarse aggregate," and
"corrupted aggregate data" from each other. The single highest-leverage next
step before any further fix work is almost certainly: **extend
`CapturedRay`/`KaptureLogger` to report `branch.material == null`
(heterogeneous flag), the raw `branchDescriptor.polar()` null-ness (not just
the defaulted `polarAlignment` value), and `lastGrowthDeferred`/
`lastFreeRefraction`** — this would let a follow-up capture session
distinguish between the remaining competing hypotheses with actual
observation instead of code-reading inference.

No source code has been modified as part of this investigation — all three
subagents were read-only. Next step is the owner's call: extend the KAPTURE
log fields as above and re-capture, or proceed directly to fixing the two
confirmed bugs (Investigation A's overlay classifier, Investigation C's
label pipeline) and re-evaluate Investigation B once real polarity/material
data is visible.
