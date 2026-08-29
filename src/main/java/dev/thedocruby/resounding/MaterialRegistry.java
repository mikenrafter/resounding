package dev.thedocruby.resounding;

import dev.thedocruby.resounding.data.MaterialCache;
import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.tag.Ident;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.thedocruby.resounding.Utils.LOGGER;

/**
 * Runtime lookup from a block to its baked acoustic {@link Material}.
 */
public final class MaterialRegistry {

    private MaterialRegistry() {}

    public static final Material DEFAULT = new Material(412.0D, 1.0D, 0.0D);

    private static volatile Map<Block, Material> baked = Map.of();
    private static volatile Set<Ident> definitionIds = Set.of();

    public static @NotNull Material material(@Nullable BlockState state) {
        if (state == null) return DEFAULT;
        Material material = baked.get(state.getBlock());
        return material == null ? DEFAULT : material;
    }

    /**
     * Short acoustic label for debug overlays: the base resounding material tag (e.g. {@code grass}).
     */
    public static String describe(@Nullable BlockState state) {
        if (state == null) return "?";
        Ident blockId = identOf(state.getBlock());
        List<Ident> acousticTags = TagRegistry.tagsOf(blockId).stream()
                .filter(definitionIds::contains)
                .sorted()
                .toList();
        if (acousticTags.isEmpty()) {
            return blockId.path();
        }
        return acousticTags.getFirst().path();
    }

    public static boolean isPopulated() {
        return !baked.isEmpty();
    }

    public static int size() {
        return baked.size();
    }

    public static void publish(Map<Ident, Material> byIdent) {
        definitionIds = Set.copyOf(byIdent.keySet());
        Map<Block, Material> table = new IdentityHashMap<>(byIdent.size());
        for (Block block : Registries.BLOCK) {
            Material material = byIdent.get(identOf(block));
            if (material != null) table.put(block, material);
        }
        baked = Map.copyOf(table);
        LOGGER.info("Resounding: published {} block materials ({} definitions resolved)",
                table.size(), byIdent.size());
    }

    public static Ident identOf(Block block) {
        return Ident.parse(Registries.BLOCK.getId(block).toString());
    }

    private static Path cachePath() {
        return FabricLoader.getInstance().getConfigDir().toAbsolutePath().resolve("resounding.cache");
    }

    public static Map<Ident, Material> recall() {
        Path path = cachePath();
        if (!Files.isRegularFile(path)) return Map.of();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return MaterialCache.read(reader);
        } catch (IOException e) {
            LOGGER.warn("Resounding: could not read material cache, regenerating", e);
            return Map.of();
        }
    }

    public static boolean save(Map<Ident, Material> materials) {
        if (materials.isEmpty()) {
            LOGGER.error("Resounding: refusing to save an empty material cache");
            return false;
        }
        try (Writer writer = Files.newBufferedWriter(cachePath(), StandardCharsets.UTF_8)) {
            MaterialCache.write(writer, materials);
        } catch (IOException e) {
            LOGGER.error("Resounding: failed saving material cache", e);
            return false;
        }
        return true;
    }
}
