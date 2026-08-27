package dev.thedocruby.resounding.tag;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The output of {@link TagResolver}: both directions of the mapping, plus what went wrong.
 *
 * <p>Both directions are exposed because resolution necessarily computes tag&nbsp;-&gt;&nbsp;blocks as
 * its intermediate and the reverse index is its inversion — publishing both is retention, not
 * construction. The forward map is also the natural surface to assert against ("tag X contains
 * exactly A, B, C"); the reverse index is what the material pipeline consumes.
 *
 * <p>Contrast the previous design, where {@code TagRegistry.tags} was assigned once and never read
 * by anything, while the registry scan never appended scanned blocks to their tags — so those tags
 * resolved empty regardless.
 */
public record Resolution(
        Map<Ident, Set<Ident>> byTag,
        Map<Ident, Set<Ident>> byBlock,
        List<Diagnostic> diagnostics
) {
    public Resolution {
        throw new UnsupportedOperationException("P2");
    }

    /** Blocks in a tag; empty (never null) for an unknown tag. */
    public Set<Ident> blocksOf(Ident tag) {
        throw new UnsupportedOperationException("P2");
    }

    /** Tags of a block; empty (never null) for an unknown block. */
    public Set<Ident> tagsOf(Ident block) {
        throw new UnsupportedOperationException("P2");
    }

    /** True if any diagnostic is {@link Diagnostic.Severity#ERROR}. */
    public boolean hasErrors() {
        throw new UnsupportedOperationException("P2");
    }
}
