package dev.thedocruby.resounding.tag;

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

    private BlockIndex() {
        throw new UnsupportedOperationException("P2");
    }

    public static Builder builder() {
        throw new UnsupportedOperationException("P2");
    }

    /** Every known block, tagged or not. */
    public Set<Ident> blocks() {
        throw new UnsupportedOperationException("P2");
    }

    /** Every tag mentioned by any block. */
    public Set<Ident> tags() {
        throw new UnsupportedOperationException("P2");
    }

    /** Tags of a block; empty (never null) for an untagged or unknown block. */
    public Set<Ident> tagsOf(Ident block) {
        throw new UnsupportedOperationException("P2");
    }

    /** Blocks carrying a tag; empty (never null) for an unknown tag. */
    public Set<Ident> blocksOf(Ident tag) {
        throw new UnsupportedOperationException("P2");
    }

    public static final class Builder {
        /** Records a block, with no tag. Idempotent. */
        public Builder block(Ident block) {
            throw new UnsupportedOperationException("P2");
        }

        /** Records a block carrying a tag. Idempotent in both directions. */
        public Builder tagged(Ident block, Ident tag) {
            throw new UnsupportedOperationException("P2");
        }

        public BlockIndex build() {
            throw new UnsupportedOperationException("P2");
        }
    }
}
