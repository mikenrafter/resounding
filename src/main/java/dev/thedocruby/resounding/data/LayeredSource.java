package dev.thedocruby.resounding.data;

import dev.thedocruby.resounding.material.RawMaterialDef;
import dev.thedocruby.resounding.tag.Diagnostic;
import dev.thedocruby.resounding.tag.Ident;
import dev.thedocruby.resounding.tag.RawTagDef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collapses ordered layers into one set of definitions.
 *
 * <p>Order, lowest to highest: generated shells, then the mod's own defaults, then enabled resource
 * packs in application order. <b>A generated shell never replaces an authored material.</b> That
 * precedence rule is the fix for the previous behaviour, where shell generation ran
 * {@code putAll} last and silently overwrote every colliding authored material with an empty
 * skeleton.
 *
 * <p>Merge semantics differ by kind, deliberately:
 * <ul>
 *   <li><b>Tags</b> union across layers, with an opt-in {@code "replace": true} to discard lower
 *       layers — vanilla datapack semantics, which pack authors already know.
 *   <li><b>Materials</b> overlay per field, so a pack retunes one property without restating the
 *       rest. {@code solute} and {@code composition} move together as a pair.
 * </ul>
 */
public final class LayeredSource {

    private LayeredSource() {}

    /**
     * Merges authored layers only.
     *
     * @param authoredLowestFirst layers in ascending precedence
     * @return a single layer named for the merge, carrying the union of all diagnostics
     */
    public static Layer merge(List<Layer> authoredLowestFirst) {
        return merge(null, authoredLowestFirst);
    }

    /**
     * Merges authored layers and lays them over generated shells.
     *
     * <p>Shells are a separate parameter rather than the first element of the list on purpose. The
     * guarantee that a generated shell never replaces an authored material is worth nothing if it
     * depends on every caller remembering to put shells first — that is the same class of mistake
     * as the original defect, where shell generation simply ran {@code putAll} last. Taking them
     * as a distinct argument makes the ordering impossible to get wrong.
     *
     * @param shells              generated skeletons, always lowest precedence
     * @param authoredLowestFirst mod defaults, then resource packs, in ascending precedence
     */
    public static Layer merge(Layer shells, List<Layer> authoredLowestFirst) {
        List<Layer> allLayers = new ArrayList<>();
        if (shells != null) {
            allLayers.add(shells);
        }
        if (authoredLowestFirst != null) {
            allLayers.addAll(authoredLowestFirst);
        }

        Map<Ident, RawTagDef> mergedTags = new LinkedHashMap<>();
        Map<Ident, String> tagOrigins = new HashMap<>();
        Map<Ident, RawMaterialDef> mergedMaterials = new LinkedHashMap<>();
        List<Diagnostic> allDiagnostics = new ArrayList<>();

        for (Layer layer : allLayers) {
            allDiagnostics.addAll(layer.diagnostics());

            for (Map.Entry<Ident, RawTagDef> entry : layer.tags().entrySet()) {
                Ident tagId = entry.getKey();
                RawTagDef newDef = entry.getValue();
                if (mergedTags.containsKey(tagId)) {
                    RawTagDef oldDef = mergedTags.get(tagId);
                    if (newDef.replace()) {
                        allDiagnostics.add(new Diagnostic.Shadowed(tagId, tagOrigins.get(tagId), layer.name()));
                    }
                    mergedTags.put(tagId, oldDef.mergeUnder(newDef));
                } else {
                    mergedTags.put(tagId, newDef);
                }
                tagOrigins.put(tagId, layer.name());
            }

            for (Map.Entry<Ident, RawMaterialDef> entry : layer.materials().entrySet()) {
                Ident matId = entry.getKey();
                RawMaterialDef newDef = entry.getValue();
                if (mergedMaterials.containsKey(matId)) {
                    RawMaterialDef oldDef = mergedMaterials.get(matId);
                    mergedMaterials.put(matId, oldDef.overlay(newDef));
                } else {
                    mergedMaterials.put(matId, newDef);
                }
            }
        }

        return new Layer("merged", mergedTags, mergedMaterials, allDiagnostics);
    }
}
