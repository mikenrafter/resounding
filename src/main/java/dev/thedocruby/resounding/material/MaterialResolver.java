package dev.thedocruby.resounding.material;

import dev.thedocruby.resounding.tag.BlockIndex;
import dev.thedocruby.resounding.tag.Diagnostic;
import dev.thedocruby.resounding.tag.Ident;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flattens, refines and bakes material definitions into runtime {@link Material}s. Pure.
 */
public final class MaterialResolver {

    /** Global ambient temperature in kelvin, applied unless a material overrides it. */
    public static final double AMBIENT_KELVIN = 287.15D;

    private MaterialResolver() {}

    /** Physical acoustic impedance range in Rayl (air ~415, dense stone ~1e7). */
    private static final double MIN_IMPEDANCE = 1e2;
    private static final double MAX_IMPEDANCE = 1e8;

    static double clampImpedance(double impedance) {
        if (!Double.isFinite(impedance) || impedance <= 0.0) {
            return MIN_IMPEDANCE;
        }
        return Acoustics.clamp(impedance, MIN_IMPEDANCE, MAX_IMPEDANCE);
    }

    /** Fills common bake defaults so elemental solutes and gases can complete the pipeline. */
    private static RawMaterialDef withBakeDefaults(RawMaterialDef raw) {
        Double temp = raw.temperature() != null ? raw.temperature() : AMBIENT_KELVIN;
        Double sw = raw.swave();
        Double lw = raw.lwave() != null ? raw.lwave() : sw;
        Double gran = raw.granularity() != null ? raw.granularity() : 1.0;
        return new RawMaterialDef(
                raw.weight(), raw.solvent(), raw.solute(), raw.composition(), raw.ratio(),
                gran, raw.melt(), raw.boil(), temp,
                raw.density(), sw, lw
        );
    }

    /** Baked materials plus everything that went wrong producing them. */
    public record Baked(Map<Ident, Material> materials, List<Diagnostic> diagnostics) {}

    /**
     * Generates a skeletal definition per block, listing that block's tags as its solutes.
     *
     * <p>The first tag is the base (composition 0, i.e. Override mode); the rest blend in. Shells
     * carry no physical properties of their own — they exist so a block inherits from whatever its
     * tags describe.
     *
     * <p><b>Solutes are ordered by {@link Ident#compareTo}.</b> A block's tags arrive as an
     * unordered set, so without a defined order "the first tag is the base" would pick a different
     * base between launches and bake a different material each time — the cache on disk would churn
     * and two clients would not agree. Sorting is what makes the result reproducible.
     *
     * <p>Every block in {@code index} gets a shell, including untagged ones, which previously fell
     * out of the pipeline entirely and ended up with no material at all.
     */
    public static Map<Ident, RawMaterialDef> shells(BlockIndex index) {
        return shells(index, null);
    }

    /**
     * @param materialDefinitionKeys when non-null, shell solutes are limited to tags that have
     *                               authored material definitions. Vanilla-only tags such as
     *                               {@code minecraft:mineable/shovel} are omitted so a single
     *                               missing reference does not fail the whole block shell.
     */
    public static Map<Ident, RawMaterialDef> shells(BlockIndex index, Set<Ident> materialDefinitionKeys) {
        Map<Ident, RawMaterialDef> result = new LinkedHashMap<>();
        for (Ident block : index.blocks()) {
            Set<Ident> tags = index.tagsOf(block);
            if (tags.isEmpty()) {
                result.put(block, new RawMaterialDef(
                        1.0, null, List.of(), List.of(), false,
                        null, null, null, null, null, null, null
                ));
            } else {
                List<Ident> sortedTags = new ArrayList<>(tags);
                sortedTags.sort(Ident::compareTo);
                if (materialDefinitionKeys != null && !materialDefinitionKeys.isEmpty()) {
                    sortedTags.removeIf(tag -> !materialDefinitionKeys.contains(tag));
                }
                if (sortedTags.isEmpty()) {
                    result.put(block, new RawMaterialDef(
                            1.0, null, List.of(), List.of(), false,
                            null, null, null, null, null, null, null
                    ));
                } else {
                    List<Double> comp = List.of(0.0);
                    result.put(block, new RawMaterialDef(
                            1.0, null, sortedTags, comp, false,
                            null, null, null, null, null, null, null
                    ));
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Resolves solute references, normalizes temperature, and bakes.
     *
     * <p>Derived properties:
     * <ul>
     *   <li>{@code impedance  = lerp(state, lwave, swave) * density}
     *   <li>{@code permeation = (1 - reflection(impedance, solvent))^(granularity * (1 + state))}
     *   <li>{@code state      = 1 - clamp(lerpProgress(temperature, melt, boil), 0, 1)} — melt/boil in °C
     * </ul>
     *
     * <p>Definitions still missing required properties after flattening are dropped with a
     * diagnostic rather than baked into nonsense. Cycles and missing solutes are reported, never
     * signalled by null.
     *
     * <p>Purity is part of the contract: {@code definitions} is not mutated. The previous
     * implementation mutated its input map while iterating it.
     */
    public static Baked resolve(Map<Ident, RawMaterialDef> definitions) {
        List<Diagnostic> diagnostics = new ArrayList<>();

        // Stage 1: Flatten solute references.
        // Node state: 0 = unvisited, 1 = visiting (on current recursion path), 2 = resolved, -1 = failed
        Map<Ident, Integer> state = new HashMap<>();
        Map<Ident, RawMaterialDef> flattened = new LinkedHashMap<>();
        List<Ident> currentPath = new ArrayList<>();

        for (Ident id : definitions.keySet()) {
            if (!state.containsKey(id)) {
                flattenNode(id, definitions, state, flattened, currentPath, diagnostics);
            }
        }

        // Stage 2: Temperature normalization and completeness check.
        Map<Ident, RawMaterialDef> readyToBake = new LinkedHashMap<>();
        for (var entry : flattened.entrySet()) {
            Ident id = entry.getKey();
            RawMaterialDef withTemp = withBakeDefaults(entry.getValue());

            if (withTemp.isComplete()) {
                readyToBake.put(id, withTemp);
            } else {
                List<String> missing = new ArrayList<>();
                if (withTemp.granularity() == null) missing.add("granularity");
                if (withTemp.melt() == null) missing.add("melt");
                if (withTemp.boil() == null) missing.add("boil");
                if (withTemp.temperature() == null) missing.add("temperature");
                if (withTemp.density() == null) missing.add("density");
                if (withTemp.swave() == null) missing.add("swave");
                if (withTemp.lwave() == null) missing.add("lwave");
                diagnostics.add(new Diagnostic.Incomplete(id, List.copyOf(missing)));
            }
        }

        // Stage 3: Bake materials (resolving solvent impedance dependencies).
        Map<Ident, Integer> bakeState = new HashMap<>();
        Map<Ident, Material> bakedMaterials = new LinkedHashMap<>();
        List<Ident> bakePath = new ArrayList<>();

        for (Ident id : readyToBake.keySet()) {
            if (!bakeState.containsKey(id)) {
                bakeNode(id, readyToBake, definitions, bakeState, bakedMaterials, bakePath, diagnostics);
            }
        }

        return new Baked(Collections.unmodifiableMap(bakedMaterials), List.copyOf(diagnostics));
    }

    private static RawMaterialDef flattenNode(
            Ident id,
            Map<Ident, RawMaterialDef> definitions,
            Map<Ident, Integer> state,
            Map<Ident, RawMaterialDef> flattened,
            List<Ident> currentPath,
            List<Diagnostic> diagnostics
    ) {
        Integer s = state.get(id);
        if (s != null) {
            if (s == 2) return flattened.get(id);
            if (s == -1) return null;
            if (s == 1) {
                int start = currentPath.indexOf(id);
                List<Ident> cycle = new ArrayList<>(currentPath.subList(start, currentPath.size()));
                cycle.add(id);
                diagnostics.add(new Diagnostic.Cycle(List.copyOf(cycle)));
                state.put(id, -1);
                return null;
            }
        }

        RawMaterialDef def = definitions.get(id);
        if (def == null) {
            state.put(id, -1);
            return null;
        }

        state.put(id, 1);
        currentPath.add(id);

        List<Ident> solutes = def.solute();
        if (solutes == null || solutes.isEmpty()) {
            currentPath.remove(currentPath.size() - 1);
            state.put(id, 2);
            RawMaterialDef normalized = withBakeDefaults(def);
            flattened.put(id, normalized);
            return normalized;
        }

        boolean failed = false;
        List<RawMaterialDef> resolvedSolutes = new ArrayList<>();
        for (Ident soluteId : solutes) {
            if (!definitions.containsKey(soluteId)) {
                diagnostics.add(new Diagnostic.MissingReference(id, soluteId));
                failed = true;
                continue;
            }
            if (currentPath.contains(soluteId)) {
                int start = currentPath.indexOf(soluteId);
                List<Ident> cycle = new ArrayList<>(currentPath.subList(start, currentPath.size()));
                cycle.add(soluteId);
                diagnostics.add(new Diagnostic.Cycle(List.copyOf(cycle)));
                failed = true;
                continue;
            }
            RawMaterialDef resolvedSolute = flattenNode(soluteId, definitions, state, flattened, currentPath, diagnostics);
            if (resolvedSolute == null) {
                failed = true;
            } else {
                resolvedSolutes.add(resolvedSolute);
            }
        }

        currentPath.remove(currentPath.size() - 1);

        if (failed) {
            state.put(id, -1);
            return null;
        }

        boolean ratio = def.ratio() != null && def.ratio();
        int length = solutes.size();
        Blend weight = new Blend(ratio);
        Blend granularity = new Blend(ratio);
        Blend melt = new Blend(ratio);
        Blend boil = new Blend(ratio);
        Blend temperature = new Blend(ratio);
        Blend density = new Blend(ratio);
        Blend swave = new Blend(ratio);
        Blend lwave = new Blend(ratio);

        for (int i = 0; i < length; i++) {
            RawMaterialDef soluteDef = resolvedSolutes.get(i);
            Double comp = def.composition() != null && i < def.composition().size() ? def.composition().get(i) : null;
            double count = comp == null ? 1.0 : comp;
            Boolean ratioUpdate = soluteDef.ratio();

            weight.add(soluteDef.weight(), 1.0, count, ratioUpdate);
            double coeff = soluteDef.weight() == null ? 1.0 : soluteDef.weight();
            granularity.add(soluteDef.granularity(), coeff, count, ratioUpdate);
            melt.add(soluteDef.melt(), coeff, count, ratioUpdate);
            boil.add(soluteDef.boil(), coeff, count, ratioUpdate);
            temperature.add(soluteDef.temperature(), coeff, count, ratioUpdate);
            // Density and wave speeds are physical properties in consistent units — do not
            // scale by molar weight; that produced impedances off by orders of magnitude.
            density.add(soluteDef.density(), 1.0, count, ratioUpdate);
            swave.add(soluteDef.swave(), 1.0, count, ratioUpdate);
            lwave.add(soluteDef.lwave(), 1.0, count, ratioUpdate);
        }

        Double w = weight.get() != null ? weight.get() : def.weight();
        Double g = granularity.get() != null ? granularity.get() : def.granularity();
        Double m = melt.get() != null ? melt.get() : def.melt();
        Double b = boil.get() != null ? boil.get() : def.boil();
        Double t = temperature.get() != null ? temperature.get() : def.temperature();
        Double d = density.get() != null ? density.get() : def.density();
        Double sw = swave.get() != null ? swave.get() : def.swave();
        Double lw = lwave.get() != null ? lwave.get() : (sw != null ? sw : def.lwave());

        RawMaterialDef flat = new RawMaterialDef(
                w, def.solvent(), def.solute(), def.composition(), def.ratio(),
                g, m, b, t, d, sw, lw
        );

        flattened.put(id, flat);
        state.put(id, 2);
        return flat;
    }

    private static Material bakeNode(
            Ident id,
            Map<Ident, RawMaterialDef> readyToBake,
            Map<Ident, RawMaterialDef> definitions,
            Map<Ident, Integer> bakeState,
            Map<Ident, Material> bakedMaterials,
            List<Ident> bakePath,
            List<Diagnostic> diagnostics
    ) {
        Integer s = bakeState.get(id);
        if (s != null) {
            if (s == 2) return bakedMaterials.get(id);
            if (s == -1) return null;
            if (s == 1) {
                int start = bakePath.indexOf(id);
                List<Ident> cycle = new ArrayList<>(bakePath.subList(start, bakePath.size()));
                cycle.add(id);
                diagnostics.add(new Diagnostic.Cycle(List.copyOf(cycle)));
                bakeState.put(id, -1);
                return null;
            }
        }

        RawMaterialDef raw = readyToBake.get(id);
        if (raw == null) {
            bakeState.put(id, -1);
            return null;
        }

        bakeState.put(id, 1);
        bakePath.add(id);

        double state = Acoustics.phaseState(raw.temperature(), raw.melt(), raw.boil());
        double velocity = Acoustics.lerp(state, raw.lwave(), raw.swave());
        double density = raw.density();
        // Shipped gas entries use g/m³ (e.g. N: 1251); solids use g/cm³. Scale gases for Rayl.
        if (state < 0.5 && density > 50.0) {
            density /= 1000.0;
        }
        double impedance = clampImpedance(velocity * density);

        double solventImpedance;
        if (raw.solvent() == null || raw.solvent().equals(id)) {
            solventImpedance = impedance * 0.8;
        } else {
            Ident solventId = raw.solvent();
            if (!definitions.containsKey(solventId)) {
                diagnostics.add(new Diagnostic.MissingReference(id, solventId));
                bakePath.remove(bakePath.size() - 1);
                bakeState.put(id, -1);
                return null;
            }
            if (bakePath.contains(solventId)) {
                int start = bakePath.indexOf(solventId);
                List<Ident> cycle = new ArrayList<>(bakePath.subList(start, bakePath.size()));
                cycle.add(solventId);
                diagnostics.add(new Diagnostic.Cycle(List.copyOf(cycle)));
                bakePath.remove(bakePath.size() - 1);
                bakeState.put(id, -1);
                return null;
            }
            Material solventMat = bakeNode(solventId, readyToBake, definitions, bakeState, bakedMaterials, bakePath, diagnostics);
            if (solventMat == null) {
                bakePath.remove(bakePath.size() - 1);
                bakeState.put(id, -1);
                return null;
            }
            solventImpedance = solventMat.impedance();
        }

        double permeation;
        if (raw.granularity() == Double.POSITIVE_INFINITY) {
            permeation = 0.0;
        } else {
            double refl = Acoustics.reflection(impedance, solventImpedance);
            if (!Double.isFinite(refl)) {
                refl = 1.0;
            }
            refl = Acoustics.clamp(refl, 0.0, 1.0);
            double exponent = raw.granularity() * (1.0 + state);
            double permeationBase = 1.0 - refl;
            permeation = Math.pow(permeationBase, exponent);
            if (!Double.isFinite(permeation)) {
                permeation = permeationBase <= 0.0 ? 0.0 : 1.0;
            }
            permeation = Acoustics.clamp(permeation, 0.0, 1.0);
        }

        Material baked = new Material(impedance, permeation, state);
        bakedMaterials.put(id, baked);
        bakePath.remove(bakePath.size() - 1);
        bakeState.put(id, 2);
        return baked;
    }
}
