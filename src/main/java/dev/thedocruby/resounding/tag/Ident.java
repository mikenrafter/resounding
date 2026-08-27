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
        throw new UnsupportedOperationException("P2");
    }

    /**
     * Parses {@code namespace:path}, or {@code path} against {@link #DEFAULT_NAMESPACE}.
     *
     * @throws IllegalArgumentException if the input is not a well-formed identifier;
     *         callers reading untrusted pack data should catch this and emit a
     *         {@link Diagnostic.ParseError} rather than letting it escape
     */
    public static Ident parse(String raw) {
        throw new UnsupportedOperationException("P2");
    }

    /** {@code namespace:path}. Round-trips through {@link #parse}. */
    @Override
    public String toString() {
        throw new UnsupportedOperationException("P2");
    }

    /** Orders by namespace, then path, so diagnostic output is stable across runs. */
    @Override
    public int compareTo(Ident other) {
        throw new UnsupportedOperationException("P2");
    }
}
