package dev.thedocruby.resounding.data;

/**
 * The one place Gson is allowed to appear.
 *
 * <p>Everything under {@code tag/} and {@code material/} takes plain Java values, which is what lets
 * the pipeline be exercised without a JSON library or a game. Parsing lives here, and so does
 * failure: malformed input becomes a {@link dev.thedocruby.resounding.tag.Diagnostic.ParseError} on
 * the returned {@link Layer}, never an exception. The previous code let
 * {@code JsonSyntaxException} propagate out of resource-pack reading, which is to say out of chunk
 * loading — one malformed pack crashed the world.
 */
public final class JsonAdapter {

    private JsonAdapter() {}

    /**
     * Parses a {@code resounding.tags.json} document.
     *
     * <p>Each entry maps a tag id to an object with any of {@code patterns}, {@code blocks},
     * {@code tagPatterns}, {@code tags}, {@code replace}.
     *
     * @param name source label used in diagnostics
     * @return a layer holding whatever parsed, plus diagnostics for whatever did not; entries that
     *         fail individually do not discard the rest of the document
     */
    public static Layer parseTags(String name, String json) {
        throw new UnsupportedOperationException("P5");
    }

    /**
     * Parses a {@code resounding.materials.json} document.
     *
     * <p>Supports nested {@code children}: a child is emitted as its own material with the parent
     * prepended as its first solute at composition 0, so it inherits the parent's properties as
     * defaults it may selectively override.
     *
     * @param name source label used in diagnostics
     */
    public static Layer parseMaterials(String name, String json) {
        throw new UnsupportedOperationException("P5");
    }
}
