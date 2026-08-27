package dev.thedocruby.resounding;

import dev.thedocruby.resounding.data.JsonAdapter;
import dev.thedocruby.resounding.data.Layer;
import dev.thedocruby.resounding.data.LayeredSource;
import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.material.MaterialResolver;
import dev.thedocruby.resounding.material.RawMaterialDef;
import dev.thedocruby.resounding.tag.BlockIndex;
import dev.thedocruby.resounding.tag.Diagnostic;
import dev.thedocruby.resounding.tag.Ident;
import dev.thedocruby.resounding.tag.Resolution;
import dev.thedocruby.resounding.tag.TagResolver;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackProfile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.thedocruby.resounding.Engine.mc;
import static dev.thedocruby.resounding.Utils.LOGGER;

/**
 * Adapter between Minecraft and the pure tagging/material pipeline.
 *
 * <p>Everything decision-making lives in {@code tag/}, {@code material/} and {@code data/}, which
 * know nothing about Minecraft and are covered by tests that run without it. This class only
 * gathers inputs from the game, hands them over, and publishes the result.
 */
public final class Cache {

    private Cache() {}

    static final String TAGS_FILE = "resounding.tags.json";
    static final String MATERIALS_FILE = "resounding.materials.json";

    /**
     * Builds the block/tag index and the baked material table, then publishes both.
     *
     * <p>Order: mod defaults, then resource packs in application order, then the block registry
     * scan; tags resolve; shells are generated from the <em>resolved</em> mapping so that
     * pattern-matched tags count as solutes; shells go underneath every authored layer; materials
     * bake.
     */
    @Environment(EnvType.CLIENT)
    public static boolean generate() {
        if (mc.world == null) return false;

        List<Layer> authored = new ArrayList<>();
        // Layer 0: the mod's own defaults, read from its container rather than by hoping the mod
        // jar shows up in the enabled-pack list. Fabric does expose mod resources as a pack, but
        // that is impl-internal behaviour and has changed before; the defaults are the one layer
        // that must never fail to load.
        readModDefaults().ifPresent(authored::add);
        // Layers 1..n: resource packs override and extend the defaults.
        authored.addAll(readResourcePacks());

        Layer merged = LayeredSource.merge(authored);

        BlockIndex index = scanBlockRegistry();
        Resolution tags = TagResolver.resolve(merged.tags(), index);
        TagRegistry.publish(tags);

        // Shells come from the resolved mapping, not the raw registry scan: a block picked up by a
        // pattern in resounding.tags.json only carries that tag after resolution, and the tag is
        // exactly what supplies its physical properties.
        Map<Ident, RawMaterialDef> shells = MaterialResolver.shells(indexOf(tags));
        Layer full = LayeredSource.merge(new Layer("shells", Map.of(), shells, List.of()), authored);

        MaterialResolver.Baked baked = MaterialResolver.resolve(full.materials());

        report("tags", tags.diagnostics());
        report("materials", baked.diagnostics());

        MaterialRegistry.publish(baked.materials());
        MaterialRegistry.save(baked.materials());
        return true;
    }

    /** Rebuilds an index from a resolution, so downstream stages see pattern-derived tags too. */
    private static BlockIndex indexOf(Resolution resolution) {
        BlockIndex.Builder builder = BlockIndex.builder();
        resolution.byBlock().forEach((block, blockTags) -> {
            builder.block(block);
            blockTags.forEach(tag -> builder.tagged(block, tag));
        });
        return builder.build();
    }

    /** Reads every block and the tags the game assigns it. */
    private static BlockIndex scanBlockRegistry() {
        BlockIndex.Builder builder = BlockIndex.builder();
        for (Block block : Registries.BLOCK) {
            Ident id = MaterialRegistry.identOf(block);
            builder.block(id);
            block.getDefaultState().streamTags().forEach(tag -> builder.tagged(id, Ident.parse(tag.id().toString())));
        }
        return builder.build();
    }

    private static Optional<Layer> readModDefaults() {
        Optional<ModContainer> container = FabricLoader.getInstance().getModContainer("resounding");
        if (container.isEmpty()) {
            LOGGER.error("Resounding: own mod container not found; shipped default materials unavailable");
            return Optional.empty();
        }
        Layer tags = readLayer("resounding:defaults(tags)",
                container.get().findPath(TAGS_FILE).orElse(null), true);
        Layer materials = readLayer("resounding:defaults(materials)",
                container.get().findPath(MATERIALS_FILE).orElse(null), false);
        return Optional.of(combine("resounding:defaults", tags, materials));
    }

    private static Layer readLayer(String name, Path path, boolean isTags) {
        if (path == null || !Files.isRegularFile(path)) return Layer.empty(name);
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return isTags ? JsonAdapter.parseTags(name, json) : JsonAdapter.parseMaterials(name, json);
        } catch (IOException e) {
            LOGGER.warn("Resounding: could not read {}", path, e);
            return Layer.empty(name);
        }
    }

    private static List<Layer> readResourcePacks() {
        List<Layer> layers = new ArrayList<>();
        Collection<ResourcePackProfile> profiles = mc.getResourcePackManager().getEnabledProfiles();
        for (ResourcePackProfile profile : profiles) {
            // ResourcePack is AutoCloseable and zip-backed packs hold file handles; the previous
            // code created one per profile per generate() and never closed any of them.
            try (ResourcePack pack = profile.createResourcePack()) {
                if (pack == null) continue;
                String name = pack.getInfo().id();
                Layer tags = readPackFile(pack, name + "(tags)", TAGS_FILE, true);
                Layer materials = readPackFile(pack, name + "(materials)", MATERIALS_FILE, false);
                if (tags.tags().isEmpty() && materials.materials().isEmpty()
                        && tags.diagnostics().isEmpty() && materials.diagnostics().isEmpty()) {
                    continue; // pack says nothing about Resounding
                }
                layers.add(combine(name, tags, materials));
            } catch (RuntimeException e) {
                LOGGER.warn("Resounding: skipping unreadable resource pack", e);
            }
        }
        return layers;
    }

    private static Layer readPackFile(ResourcePack pack, String name, String file, boolean isTags) {
        InputSupplier<InputStream> supplier = pack.openRoot(file);
        if (supplier == null) return Layer.empty(name);
        try (InputStream in = supplier.get()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return isTags ? JsonAdapter.parseTags(name, json) : JsonAdapter.parseMaterials(name, json);
        } catch (IOException e) {
            LOGGER.warn("Resounding: could not read {} from pack {}", file, name, e);
            return Layer.empty(name);
        }
    }

    private static Layer combine(String name, Layer tags, Layer materials) {
        List<Diagnostic> diagnostics = new ArrayList<>(tags.diagnostics());
        diagnostics.addAll(materials.diagnostics());
        return new Layer(name, tags.tags(), materials.materials(), diagnostics);
    }

    /**
     * Summarizes diagnostics instead of logging each one.
     *
     * <p>A vanilla registry scan produces thousands of blocks carrying tags nobody has written a
     * material for, and each is a legitimate WARN. Printed individually that is unreadable and
     * would bury the errors that matter, so the counts go to INFO and the detail to DEBUG.
     */
    private static void report(String stage, List<Diagnostic> diagnostics) {
        if (diagnostics.isEmpty()) return;
        Map<String, Integer> counts = new LinkedHashMap<>();
        int errors = 0;
        for (Diagnostic d : diagnostics) {
            counts.merge(d.getClass().getSimpleName(), 1, Integer::sum);
            if (d.severity() == Diagnostic.Severity.ERROR) errors++;
        }
        if (errors > 0) {
            LOGGER.warn("Resounding: {} stage produced {} diagnostics ({} errors): {}",
                    stage, diagnostics.size(), errors, counts);
            diagnostics.stream()
                    .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
                    .limit(20)
                    .forEach(d -> LOGGER.warn("  {}", d.message()));
        } else {
            LOGGER.info("Resounding: {} stage produced {} diagnostics: {}", stage, diagnostics.size(), counts);
        }
        if (LOGGER.isDebugEnabled()) diagnostics.forEach(d -> LOGGER.debug("  {}", d.message()));
    }
}
