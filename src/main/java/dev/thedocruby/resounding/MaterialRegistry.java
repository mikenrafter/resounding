package dev.thedocruby.resounding;

import dev.thedocruby.resounding.material.Material;

import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.MathHelper;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.FileSystems;
import java.util.*;

import static dev.thedocruby.resounding.Utils.LOGGER;

/**
 * Owns the material lifecycle: deserialization, flattening, refinement, baking, persistence, and runtime lookup.
 *
 * The material pipeline runs in four stages during Cache.generate():
 *   1. deserializeMaterials — parse JSON entries from resource packs
 *   2. shellMaterials       — create skeletal materials for registry blocks
 *   3. flattenMaterials     — recursively resolve solute references
 *   4. refineMaterials      — normalize temperature, filter incomplete, bake to final Materials
 *
 * At runtime, material(BlockState) is the fast lookup used by raycasting.
 */
public class MaterialRegistry {
    private MaterialRegistry() {}

    public static HashMap<String, Material> materials = new HashMap<>();

    /** Looks up the acoustic Material for a block state. Falls back to "air" if unmapped. */
    @Environment(EnvType.CLIENT) // TODO: is this method needed on server side?
    public static @NotNull Material material(@Nullable BlockState state) {
        return materials.getOrDefault(state.getBlock().getName(), materials.get("air"));
    }

    /** Recalls baked materials from the config-dir cache file. Returns false if cache is empty or missing. */
    public static boolean recall() {
        HashMap<String, Material> temp = Utils.recall("resounding.cache", Utils.token(MaterialRegistry.materials), (LinkedTreeMap value) -> new Material(
                (double) value.getOrDefault("impedance", 350),
                (double) value.getOrDefault("permeation", 1),
                (double) value.getOrDefault("state", 1)
        ));
        if (temp.isEmpty()) return false;
        else MaterialRegistry.materials = temp;
        return true;
    }

    /** Serializes baked materials to the config-dir cache file. Returns false on failure. */
    public static boolean save() {
        if (MaterialRegistry.materials.isEmpty()) {
            LOGGER.error("Cannot save, materials undefined");
            return false;
        }
        String json = new Gson().toJson(MaterialRegistry.materials, Utils.token(MaterialRegistry.materials));
        String name = FabricLoader.getInstance().getConfigDir().toAbsolutePath() + FileSystems.getDefault().getSeparator() + "resounding.cache";
        try {
            FileWriter writer = new FileWriter(name);
            writer.write(json);
            writer.close();
        } catch (IOException e) {
            LOGGER.error("Failed saving materials", e);
            return false;
        }
        return true;
    }

    /**
     * Pipeline stage 4b: Converts a single resolved RawMaterial into a baked Material.
     *
     * Computes three derived acoustic properties from the raw physical values:
     *   impedance  = velocity × density  (determines reflection at material boundaries)
     *   permeation = (1 - reflection)^(granularity × (1 + state))  (sound transmission through material)
     *   state      = lerp_progress(temperature, melt, boil)  (solid=0 → liquid → gas=1)
     *
     * Uses memoize for deduplication since multiple blocks may share the same material.
     */
    static Material bake(HashMap<String, RawMaterial> in, HashMap<String, Material> out, String key) {
        return Utils.memoize(in, out, key, (getter, raw) -> {
            double state = MathHelper.getLerpProgress(raw.temperature(), raw.melt(), raw.boil());
            double velocity = MathHelper.lerp(state, raw.lwave(), raw.swave());
            double impedance = velocity * raw.density();

            double solvent;
            if (raw.solvent() == null)
                solvent = impedance * 0.8;
            else {
                Material solve = getter.apply(raw.solvent());
                if (solve == null) return null;
                solvent = solve.impedance();
            }

            double permeation;
            // java doesn't handle x.pow(infinity) when x.range(0, < 1) correctly
            if (raw.granularity() == Double.POSITIVE_INFINITY)
                permeation = 0;
            else
                permeation = Math.pow(1 - Physics.reflection(impedance, solvent), raw.granularity() * (1 + state));

            return new Material(impedance, permeation, state);
        }, false);
    }

    /**
     * Pipeline stage 1: Deserializes a single material entry from a resource pack JSON.
     *
     * Recursively processes "children" blocks, prepending the parent as the first
     * solute with composition[0]=0 (Override mode) so children inherit parent properties
     * as defaults that can be selectively overridden.
     */
    @SuppressWarnings("unchecked")
    static HashMap<String, RawMaterial> deserializeMaterials(String name, LinkedTreeMap value) {
        LinkedTreeMap<String, LinkedTreeMap> children =
                (LinkedTreeMap<String, LinkedTreeMap>) value.get("children");
        HashMap<String, RawMaterial> map = new HashMap<>();
        if (children != null) {
            for (String key : children.keySet()) {
                LinkedTreeMap modified = children.get(key);
                String[] solute = Utils.asArray(new String[0], modified.get("solute"));
                Double[] composition = Utils.asArray(new Double[0], modified.get("composition"));
                solute = ArrayUtils.addFirst(solute, name);
                composition = ArrayUtils.addFirst(composition, 0D);
                modified.put("solute", solute);
                modified.put("composition", composition);
                map.putAll(MaterialRegistry.deserializeMaterials(key, modified));
            }
        }
        map.put(name,
            new RawMaterial(null,
                (Double) value.get("weight"),
                (String) value.get("solvent"),
                Utils.asArray(new String[0], value.get("solute")),
                Utils.asArray(new Double[0], value.get("composition")),
                (Boolean) value.get("ratio"),
                (Double) value.get("granularity"),
                (Double) value.get("melt"),
                (Double) value.get("boil"),
                (Double) value.get("temperature"),
                (Double) value.get("density"),
                (Double) value.get("swave"),
                (Double) value.get("lwave")
            )
        );
        return map;
    }

    /**
     * Pipeline stage 3: Resolves all solute references and merges inherited property values.
     *
     * Each RawMaterial's solute array references other materials. This method recursively
     * resolves those references (via memoize) and uses Property accumulators to blend
     * values according to Override/Adjust/Weighted modes (see Property.add).
     * Returns fully-resolved RawMaterials with concrete physical property values.
     */
    static HashMap<String, RawMaterial> flattenMaterials(HashMap<String, RawMaterial> in) {
        HashMap<String, RawMaterial> flat = new HashMap<>();
        for (String key : in.keySet()) {
            // NOTE: don't access any non-static closure variables other than (getter, raw) inside the calculation phase
            Utils.memoize(in, flat, key, (getter, raw) -> {
                String[] solute = raw.solute() == null ? new String[0] : raw.solute();
                Double[] composition = raw.composition() == null ? new Double[0] : raw.composition();
                int length = solute.length;
                boolean ratio = raw.ratio() != null && raw.ratio();
                if (length > 0) {
                    Property weight = new Property(ratio);
                    Property granularity = new Property(ratio);
                    Property melt = new Property(ratio);
                    Property boil = new Property(ratio);
                    Property temperature = new Property(ratio);
                    Property density = new Property(ratio);
                    Property swave = new Property(ratio);
                    Property lwave = new Property(ratio);
                    LinkedList<String> errors = new LinkedList<>();
                    for (int i = 0; i < length; i++) {
                        String name = solute[i];
                        RawMaterial material = getter.apply(name);
                        if (material == null) {
                            errors.add(name);
                            continue;
                        }
                        final double count = composition[i] == null ? 1 : composition[i];
                        weight.add(material.weight(), 1D, count, material.ratio());
                        final double coefficient = material.weight() == null ? 1 : material.weight();
                        granularity.add(material.granularity(), coefficient, count, material.ratio());
                        melt.add(material.melt(), coefficient, count, material.ratio());
                        boil.add(material.boil(), coefficient, count, material.ratio());
                        temperature.add(material.temperature(), coefficient, count, material.ratio());
                        density.add(material.density(), coefficient, count, material.ratio());
                        swave.add(material.swave(), coefficient, count, material.ratio());
                        lwave.add(material.lwave(), coefficient, count, material.ratio());
                    }
                    if (!errors.isEmpty()) {
                        for (String error : errors)
                            LOGGER.error("{} in {} is invalid or cyclical", error, key);
                        return null;
                    }
                    raw = new RawMaterial(null,
                            weight.get(),
                            raw.solvent(), raw.solute(), raw.composition(),
                            ratio,
                            granularity.get(),
                            melt.get(), boil.get(),
                            temperature.get(),
                            density.get(),
                            swave.get(), lwave.get()
                    );
                }
                return raw;
            }, false);
        }
        return flat;
    }

    /**
     * Pipeline stage 4: Normalizes temperature and bakes RawMaterials into final Materials.
     *
     * First overrides all temperature values with a global average (287.15K).
     * Then filters out any RawMaterials still missing required physical properties
     * (these are shells that couldn't be fully resolved during flattening).
     * Finally calls bake() to compute derived acoustic properties (impedance, permeation, state).
     */
    public static HashMap<String, Material> refineMaterials(HashMap<String, RawMaterial> rawMaterials) {
        final double temperature = 287.15D;
        rawMaterials.forEach((String key, RawMaterial raw) -> {
            rawMaterials.put(key,
                    new RawMaterial(null,
                            raw.weight(),
                            raw.solvent(), raw.solute(), raw.composition(),
                            raw.ratio(),
                            raw.granularity(),
                            raw.melt(), raw.boil(),
                            temperature,
                            raw.density(),
                            raw.swave(), raw.lwave()
                    )
            );
        });

        HashMap<String, Material> refined = new HashMap<>();
        for (String key : rawMaterials.keySet()) {
            RawMaterial raw = rawMaterials.get(key);
            if (raw.granularity() == null
                    || raw.melt() == null || raw.boil() == null
                    || raw.density() == null
                    || raw.temperature() == null
                    || raw.swave() == null || raw.lwave() == null
            ) {
                LOGGER.warn("Material '{}' dropped: missing required physical properties (granularity={}, melt={}, boil={}, density={}, temp={}, swave={}, lwave={})",
                        key, raw.granularity(), raw.melt(), raw.boil(), raw.density(), raw.temperature(), raw.swave(), raw.lwave());
                continue;
            }
            bake(rawMaterials, refined, key);
        }
        return refined;
    }

    /** Convenience: runs the flatten → refine pipeline on deserialized+shell materials. */
    static HashMap<String, Material> finalizeMaterials(HashMap<String, RawMaterial> materials) {
        return refineMaterials(flattenMaterials(materials));
    }

    /**
     * Pipeline stage 2: Creates skeletal RawMaterials for every block in the registry.
     *
     * Each shell material lists its associated tags as solute components with
     * the first tag as the base (composition[0]=0 → Override mode in Property.add).
     * These shells are intentionally incomplete — they carry no physical properties,
     * relying on flattenMaterials to resolve values from their tag-based solutes.
     */
    static HashMap<String, RawMaterial> shellMaterials(HashMap<String, LinkedList<String>> blocks) {
        HashMap<String, RawMaterial> materials = new HashMap<>();
        for (String key : blocks.keySet()) {
            String[] solute = blocks.get(key).toArray(new String[0]);
            Double[] composition = new Double[solute.length];
            composition[0] = 0D;
            materials.put(key, new RawMaterial(null,
                    1D, null, solute, composition,
                    false,
                    null, null, null,
                    null, null, null,
                    null
            ));
        }
        return materials;
    }
}
