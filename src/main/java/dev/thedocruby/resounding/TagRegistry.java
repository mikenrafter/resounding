package dev.thedocruby.resounding;

import com.google.gson.internal.LinkedTreeMap;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Resolves raw tag definitions from resource packs into finalized block-to-tag mappings.
 *
 * Tags are defined in resounding.tags.json with patterns and explicit block lists.
 * Resolution recursively expands tag references (tagPatterns/tags fields) and
 * pattern-matches block names to build the final Tag objects. The reverse mapping
 * (block → tag list) is maintained as a side-effect for use by the material pipeline.
 */
public class TagRegistry {
    private TagRegistry() {}

    public static HashMap<String, Tag> tags = new HashMap<>();

    /** Deserializes a single tag entry from a resource pack JSON into a RawTag. */
    static HashMap<String, RawTag> deserializeTag(String name, LinkedTreeMap value) {
        HashMap<String, RawTag> map = new HashMap<>();
        map.put(name, new RawTag(
                Utils.toPatterns(Utils.asArray(new String[0], value.get("patterns"))),
                Utils.asArray(new String[0], value.get("blocks")),
                Utils.toPatterns(Utils.asArray(new String[0], value.get("tagPatterns"))),
                Utils.asArray(new String[0], value.get("tags"))
        ));
        return map;
    }

    /**
     * Resolves tag references and pattern-matches to produce finalized Tags.
     *
     * Mutates the blocks map as a side-effect: when a pattern match adds a block
     * to a tag, the reverse mapping (block → tag list) is also updated.
     */
    public static HashMap<String, Tag> flattenTags(HashMap<String, RawTag> tags, HashMap<String, LinkedList<String>> blocks) {
        HashMap<String, Tag> output = new HashMap<>();
        List<String> tagNames = tags.keySet().stream().toList();
        for (String name : tagNames) {
            // NOTE: don't access any non-static closure variables other than (getter, raw) inside the calculation phase
            //       This will change how the compiler sees the lambda, (see: lambda closures)
            //       and will recreate it on every use (resulting in a large performance hit)
            Utils.memoize(tags, output, name, (getter, raw) -> {
                LinkedList<String> self = new LinkedList<>(Arrays.stream(
                    tags.getOrDefault(name, new RawTag(null, new String[0], null, null)).blocks()
                ).toList());

                Utils.granularFilter(tagNames, raw.tagPatterns(), raw.tags()).map(getter).forEach(tag -> {
                    // when underlying tag isn't present -> this happens when a tag self references
                    // in order to expand an existing tag in a modpack.
                    if (tag == null) return;
                    self.addAll(Arrays.stream(tag.blocks()).toList());
                });
                Utils.granularFilter(blocks.keySet().stream().toList(), raw.patterns(), raw.blocks()).forEach(block -> {
                    self.add(block);
                    Utils.update(blocks, block, name);
                });

                return new Tag(self.toArray(new String[]{}));
            });
        }
        return output;
    }

    static HashMap<String, Tag> finalizeTags(HashMap<String, RawTag> tags, HashMap<String, LinkedList<String>> blocks) {
        return flattenTags(tags, blocks);
    }
}
