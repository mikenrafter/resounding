package dev.thedocruby.resounding.tag;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An immutable, set-valued, bidirectional block &lt;-&gt; tag index.
 *
 * <p>Set-valued deliberately. The previous reverse map was a {@code LinkedList} appended to
 * unconditionally by {@code Utils.update}, so a block matched by both a registry tag and a pattern
 * was recorded twice — and since a block's tags become its material's solutes, the duplicate
 * silently doubled that solute's weight in the blend. Deduplication here is a correctness fix, not
 * a tidiness one.
 *
 * <p>Blocks with no tags at all are still members. The old pipeline only ever admitted a block to
 * its map as a side effect of finding a tag for it, so untagged blocks got no shell material and no
 * acoustic material, ever.
 */
public final class BlockIndex {

    private final Map<Ident, Set<Ident>> blockToTags;
    private final Map<Ident, Set<Ident>> tagToBlocks;

    private BlockIndex(Map<Ident, Set<Ident>> blockToTags, Map<Ident, Set<Ident>> tagToBlocks) {
        this.blockToTags = blockToTags;
        this.tagToBlocks = tagToBlocks;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Every known block, tagged or not. */
    public Set<Ident> blocks() {
        return blockToTags.keySet();
    }

    /** Every tag mentioned by any block. */
    public Set<Ident> tags() {
        return tagToBlocks.keySet();
    }

    /** Tags of a block; empty (never null) for an untagged or unknown block. */
    public Set<Ident> tagsOf(Ident block) {
        return blockToTags.getOrDefault(block, Set.of());
    }

    /** Blocks carrying a tag; empty (never null) for an unknown tag. */
    public Set<Ident> blocksOf(Ident tag) {
        return tagToBlocks.getOrDefault(tag, Set.of());
    }

    public static final class Builder {
        private final Map<Ident, Set<Ident>> blockToTags = new HashMap<>();
        private final Map<Ident, Set<Ident>> tagToBlocks = new HashMap<>();

        /** Records a block, with no tag. Idempotent. */
        public Builder block(Ident block) {
            blockToTags.computeIfAbsent(block, b -> new HashSet<>());
            return this;
        }

        /** Records a block carrying a tag. Idempotent in both directions. */
        public Builder tagged(Ident block, Ident tag) {
            blockToTags.computeIfAbsent(block, b -> new HashSet<>()).add(tag);
            tagToBlocks.computeIfAbsent(tag, t -> new HashSet<>()).add(block);
            return this;
        }

        public BlockIndex build() {
            Map<Ident, Set<Ident>> blockToTagsCopy = new HashMap<>();
            for (var entry : blockToTags.entrySet()) {
                blockToTagsCopy.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
            Map<Ident, Set<Ident>> tagToBlocksCopy = new HashMap<>();
            for (var entry : tagToBlocks.entrySet()) {
                tagToBlocksCopy.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
            return new BlockIndex(Map.copyOf(blockToTagsCopy), Map.copyOf(tagToBlocksCopy));
        }
    }
}
