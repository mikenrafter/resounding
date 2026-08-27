package dev.thedocruby.resounding.tag;

import java.util.List;

/**
 * A typed, inspectable resolution problem.
 *
 * <p>Replaces the previous error channel, which was {@code null}. The old {@code Utils.memoize}
 * returned {@code null} for "cycle detected", "key absent", and "value legitimately null" alike,
 * logged {@code "{} is invalid or cyclical"} for all three, and left the in-progress {@code null}
 * marker in the output map as a permanent negative cache — while {@code remove=true} had already
 * destroyed the input entry, so nothing could be retried. Callers could neither distinguish the
 * cases nor recover from any of them.
 */
public sealed interface Diagnostic {

    Severity severity();

    /** Human-readable, suitable for a log line. */
    String message();

    enum Severity { WARN, ERROR }

    /** A tag or material reference cycle. {@code path} is the cycle in traversal order. */
    record Cycle(List<Ident> path) implements Diagnostic {
        @Override public Severity severity() { throw new UnsupportedOperationException("P2"); }
        @Override public String message() { throw new UnsupportedOperationException("P2"); }
    }

    /**
     * A reference to something that does not exist.
     *
     * <p>Note this is a {@link Severity#WARN}, not an error: a pack legitimately references tags
     * from mods that may not be installed, and the documented "self-reference to extend an existing
     * tag" idiom relies on unresolved references being survivable.
     */
    record MissingReference(Ident from, Ident to) implements Diagnostic {
        @Override public Severity severity() { throw new UnsupportedOperationException("P2"); }
        @Override public String message() { throw new UnsupportedOperationException("P2"); }
    }

    /** A regex in a tag definition failed to compile. Previously an escaping {@code PatternSyntaxException}. */
    record BadPattern(Ident owner, String pattern, String error) implements Diagnostic {
        @Override public Severity severity() { throw new UnsupportedOperationException("P2"); }
        @Override public String message() { throw new UnsupportedOperationException("P2"); }
    }

    /**
     * Malformed input data. Previously an uncaught {@code JsonSyntaxException} propagating out of
     * resource-pack parsing, i.e. out of chunk loading.
     */
    record ParseError(String source, String detail) implements Diagnostic {
        @Override public Severity severity() { throw new UnsupportedOperationException("P2"); }
        @Override public String message() { throw new UnsupportedOperationException("P2"); }
    }

    /**
     * A definition was dropped because it still lacked properties needed to bake after flattening.
     *
     * <p>Distinct from {@link MissingReference}: nothing was missing from the graph, the material
     * simply never acquired a density, a wave velocity, or similar. Previously this was a bare
     * {@code LOGGER.warn} with no structured trace of which property was absent.
     *
     * @param missing names of the absent properties, for a message a pack author can act on
     */
    record Incomplete(Ident subject, List<String> missing) implements Diagnostic {
        @Override public Severity severity() { throw new UnsupportedOperationException("P2"); }
        @Override public String message() { throw new UnsupportedOperationException("P2"); }
    }

    /** A higher layer declared {@code "replace": true} and discarded content from a lower one. */
    record Shadowed(Ident subject, String lowerLayer, String higherLayer) implements Diagnostic {
        @Override public Severity severity() { throw new UnsupportedOperationException("P2"); }
        @Override public String message() { throw new UnsupportedOperationException("P2"); }
    }
}
