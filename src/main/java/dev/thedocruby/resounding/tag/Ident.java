package dev.thedocruby.resounding.tag;

/**
 * A namespaced identifier — the single key domain for the whole pipeline.
 *
 * <p>The pipeline this replaces used three incompatible key forms interchangeably in one
 * {@code HashMap<String, ?>}: translation keys ({@code block.minecraft.stone}), tag ids
 * ({@code minecraft:mineable/axe}), and free-form names from resource packs. The runtime lookup
 * additionally keyed on {@code Block.getName()}, which returns a {@code MutableText}, so it
 * type-checked through {@code Map.getOrDefault(Object, V)} and missed on every single call.
 *
 * <p>Making the key a distinct type rather than a {@code String} is the point: a translation key
 * can no longer be silently handed to something expecting a block id.
 */
public record Ident(String namespace, String path) implements Comparable<Ident> {

    /** Namespace assumed when a raw string carries none, matching vanilla behaviour. */
    public static final String DEFAULT_NAMESPACE = "minecraft";

    /**
     * @throws IllegalArgumentException if either part is empty or contains a separator
     */
    public Ident {
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalArgumentException("namespace must not be empty");
        }
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        if (namespace.indexOf(':') >= 0) {
            throw new IllegalArgumentException("namespace must not contain ':': " + namespace);
        }
        if (path.indexOf(':') >= 0) {
            throw new IllegalArgumentException("path must not contain ':': " + path);
        }
    }

    /**
     * Parses {@code namespace:path}, or {@code path} against {@link #DEFAULT_NAMESPACE}.
     *
     * @throws IllegalArgumentException if the input is not a well-formed identifier;
     *         callers reading untrusted pack data should catch this and emit a
     *         {@link Diagnostic.ParseError} rather than letting it escape
     */
    public static Ident parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("identifier must not be null");
        }
        int first = raw.indexOf(':');
        if (first < 0) {
            return new Ident(DEFAULT_NAMESPACE, raw);
        }
        if (raw.indexOf(':', first + 1) >= 0) {
            throw new IllegalArgumentException("identifier must contain at most one ':': " + raw);
        }
        return new Ident(raw.substring(0, first), raw.substring(first + 1));
    }

    /** {@code namespace:path}. Round-trips through {@link #parse}. */
    @Override
    public String toString() {
        return namespace + ":" + path;
    }

    /** Orders by namespace, then path, so diagnostic output is stable across runs. */
    @Override
    public int compareTo(Ident other) {
        int byNamespace = namespace.compareTo(other.namespace);
        if (byNamespace != 0) {
            return byNamespace;
        }
        return path.compareTo(other.path);
    }
}
