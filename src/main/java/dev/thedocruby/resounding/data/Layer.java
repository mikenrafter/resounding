package dev.thedocruby.resounding.data;

import dev.thedocruby.resounding.material.RawMaterialDef;
import dev.thedocruby.resounding.tag.Diagnostic;
import dev.thedocruby.resounding.tag.Ident;
import dev.thedocruby.resounding.tag.RawTagDef;

import java.util.List;
import java.util.Map;

/**
 * One source of definitions — the mod's own defaults, or a single resource pack.
 *
 * @param name        where this came from, for diagnostics
 * @param tags        tag definitions in this layer
 * @param materials   material definitions in this layer
 * @param diagnostics problems encountered parsing this layer
 */
public record Layer(
        String name,
        Map<Ident, RawTagDef> tags,
        Map<Ident, RawMaterialDef> materials,
        List<Diagnostic> diagnostics
) {
    public Layer {
        throw new UnsupportedOperationException("P5");
    }

    public static Layer empty(String name) {
        throw new UnsupportedOperationException("P5");
    }
}
