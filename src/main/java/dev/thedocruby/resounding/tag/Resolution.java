package dev.thedocruby.resounding.tag;

import java.util.HashMap;
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
        byTag = byTag == null ? Map.of() : copyOfSetMap(byTag);
        byBlock = byBlock == null ? Map.of() : copyOfSetMap(byBlock);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    private static Map<Ident, Set<Ident>> copyOfSetMap(Map<Ident, Set<Ident>> source) {
        Map<Ident, Set<Ident>> copy = new HashMap<>();
        for (var entry : source.entrySet()) {
            copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }

    /** Blocks in a tag; empty (never null) for an unknown tag. */
    public Set<Ident> blocksOf(Ident tag) {
        return byTag.getOrDefault(tag, Set.of());
    }

    /** Tags of a block; empty (never null) for an unknown block. */
    public Set<Ident> tagsOf(Ident block) {
        return byBlock.getOrDefault(block, Set.of());
    }

    /** True if any diagnostic is {@link Diagnostic.Severity#ERROR}. */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
    }
}
