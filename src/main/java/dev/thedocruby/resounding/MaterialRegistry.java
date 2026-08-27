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
import java.util.Map;

import static dev.thedocruby.resounding.Utils.LOGGER;

/**
 * Runtime lookup from a block to its baked acoustic {@link Material}.
 *
 * <p>This is the adapter over the pure pipeline: resolution happens in
 * {@code dev.thedocruby.resounding.material}, and this class only holds the baked result and
 * answers queries against it during raycasting.
 *
 * <p>Two things here are load-bearing.
 *
 * <p><b>The lookup key.</b> The previous implementation asked
 * {@code materials.getOrDefault(state.getBlock().getName(), ...)}. {@code Block.getName()} returns a
 * {@code MutableText}, not a {@code String}, so passing it to a {@code HashMap<String, Material>}
 * type-checked only because {@code Map.getOrDefault} erases its key to {@code Object} — and missed
 * on literally every call. Every block in the world resolved to the {@code "air"} fallback, which
 * was itself absent, so a {@code @NotNull} method returned {@code null}. Keying on the
 * {@code Block} instance removes the possibility of that mistake.
 *
 * <p><b>Publication.</b> Generation runs on the chunk-loading thread while the octree pool reads
 * this map concurrently. The old code mutated a bare {@code HashMap} in place via {@code putAll}
 * with no safe publication at all. Here the baked table is immutable and swapped in through a
 * single {@code volatile} write, so a reader sees either the whole old table or the whole new one.
 */
public final class MaterialRegistry {

    private MaterialRegistry() {}

    /**
     * Fallback for blocks with no resolved material. Air-like and deliberately permeable, so an
     * unmapped block is acoustically transparent rather than a silent wall.
     */
    public static final Material DEFAULT = new Material(412.0D, 1.0D, 0.0D);

    /** Baked lookup table. Immutable once published; replaced wholesale, never mutated. */
    private static volatile Map<Block, Material> baked = Map.of();

    /**
     * Looks up the acoustic material for a block state.
     *
     * <p>Never returns null and never throws, including for a null state — {@code Cast} and
     * {@code OctreeManager} call this from pool threads where an exception is swallowed and a null
     * surfaces later as an unexplained NPE in {@code Branch.material.equals}.
     */
    public static @NotNull Material material(@Nullable BlockState state) {
        if (state == null) return DEFAULT;
        Material material = baked.get(state.getBlock());
        return material == null ? DEFAULT : material;
    }

    /** True once a generation pass has published a table. */
    public static boolean isPopulated() {
        return !baked.isEmpty();
    }

    public static int size() {
        return baked.size();
    }

    /**
     * Publishes a baked table, mapping each material back onto the block instances it belongs to.
     *
     * <p>Identity semantics are correct and intentional: blocks are singletons held by the registry.
     */
    public static void publish(Map<Ident, Material> byIdent) {
        Map<Block, Material> table = new IdentityHashMap<>(byIdent.size());
        for (Block block : Registries.BLOCK) {
            Material material = byIdent.get(identOf(block));
            if (material != null) table.put(block, material);
        }
        baked = Map.copyOf(table);
        LOGGER.info("Resounding: published {} block materials ({} definitions resolved)",
                table.size(), byIdent.size());
    }

    /** The canonical identifier for a block — the same domain vanilla tags and resource packs use. */
    public static Ident identOf(Block block) {
        return Ident.parse(Registries.BLOCK.getId(block).toString());
    }

    // -- persistence ------------------------------------------------------------------------

    private static Path cachePath() {
        return FabricLoader.getInstance().getConfigDir().toAbsolutePath().resolve("resounding.cache");
    }

    /**
     * Recalls a previously baked table from the config directory.
     *
     * @return the recalled materials, or an empty map if there is nothing usable on disk
     */
    public static Map<Ident, Material> recall() {
        Path path = cachePath();
        if (!Files.isRegularFile(path)) return Map.of();
        // try-with-resources: the previous implementation leaked its reader on every call.
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return MaterialCache.read(reader);
        } catch (IOException e) {
            LOGGER.warn("Resounding: could not read material cache, regenerating", e);
            return Map.of();
        }
    }

    /** Writes the baked table to the config directory. Returns false on failure. */
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
