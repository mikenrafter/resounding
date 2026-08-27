package dev.thedocruby.resounding;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackProfile;

import java.util.*;
import java.util.regex.Pattern;

import static dev.thedocruby.resounding.Engine.mc;

/**
 * Facade that orchestrates the material/tag generation pipeline.
 *
 * Delegates to focused registries:
 *   {@link MaterialRegistry}  — material lifecycle and runtime lookup
 *   {@link TagRegistry}       — tag resolution from resource packs + game registry
 *   {@link OctreeManager}     — per-chunk spatial index for raycasting
 *   {@link SoundClassifier}   — sound name classification and position adjustment
 */
public class Cache {
    private Cache() {}

    /**
     * Generates material and tag mappings from enabled resource packs and the game block registry.
     *
     * Pipeline: read resource packs → scan block registry tags → resolve tags →
     * shell materials for blocks → flatten → refine → bake → save to disk.
     */
    @Environment(EnvType.CLIENT)
    public static boolean generate() {
        if (mc.world == null) return false;

        HashMap<String, RawMaterial> materials = new HashMap<>();
        HashMap<String, RawTag> tags = new HashMap<>();

        // read material and tag definitions from enabled resource packs
        Collection<ResourcePackProfile> list = mc.getResourcePackManager().getEnabledProfiles();
        for (ResourcePackProfile profile : list) {
            ResourcePack pack = profile.createResourcePack();
            materials.putAll(Utils.resource(pack, new String[]{"resounding.materials.json"}, Utils.token(materials), MaterialRegistry::deserializeMaterials));
            tags.putAll(Utils.resource(pack, new String[]{"resounding.tags.json"}, Utils.token(tags), TagRegistry::deserializeTag));
        }

        // scan the game block registry for vanilla/modded tags
        HashMap<String, LinkedList<String>> blocks = new HashMap<>();
        Registries.BLOCK.forEach((Block block) -> {
            String name = block.getTranslationKey();
            block.getDefaultState().streamTags()
                    .forEach((tag) -> {
                        String id = tag.id().toString();
                        tags.put(id,
                                new RawTag(new Pattern[0],
                                        tags.getOrDefault(
                                                id, new RawTag(null, new String[0], null, null)
                                        ).blocks(),
                                        new Pattern[0], new String[0])
                        );
                        Utils.update(blocks, name, id);
                    });
        });

        TagRegistry.tags = TagRegistry.finalizeTags(tags, blocks);

        // generate material mappings for blocks
        materials.putAll(MaterialRegistry.shellMaterials(blocks));
        MaterialRegistry.materials.putAll(MaterialRegistry.finalizeMaterials(materials));

        MaterialRegistry.save();
        return true;
    }
}
