package dev.thedocruby.resounding.tag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Expands tag definitions into concrete block sets. Pure.
 *
 * <p>Expansion has three sources, unioned: blocks stated outright, blocks whose id matches one of
 * the tag's regexes, and the contents of other tags pulled in by id or by regex over tag ids.
 *
 * <p>This is the piece that was most wrong. The previous implementation
 * ({@code TagRegistry.flattenTags}) had its memoization lambda close over the enclosing
 * <em>loop variable</em> rather than the key being memoized, so when the memoizer recursed to
 * resolve a referenced tag, the body still operated on the outer tag's name — writing the reverse
 * index under the wrong tag entirely. The file's own comment warned against exactly this. It also
 * read a tag's own explicit block list back out of the input map <em>after</em> the memoizer had
 * removed the entry, so that list was unconditionally discarded.
 *
 * <p>Both bugs share a root cause: resolution was written as mutation of shared maps. Hence the
 * contract below: {@link #resolve} keeps every mutable structure as a local variable of one call,
 * nothing is shared object state, and nothing survives past the call that built it.
 */
public final class TagResolver {

    private TagResolver() {}

    /**
     * Resolves definitions against an index of known blocks.
     *
     * <p><b>Purity is part of the contract, and is tested.</b> Neither argument is mutated, and
     * resolving the same inputs twice yields equal results. Nothing is memoized across calls.
     *
     * <p>Failures are reported, never thrown and never signalled by null:
     * <ul>
     *   <li>a reference cycle yields {@link Diagnostic.Cycle} and the participating tags resolve to
     *       the union of everything reachable without traversing the cycle, rather than to nothing;
     *   <li>a reference to an unknown tag yields {@link Diagnostic.MissingReference} and is skipped,
     *       preserving the "self-reference to extend an existing tag" idiom;
     *   <li>a regex that does not compile yields {@link Diagnostic.BadPattern} and is skipped.
     * </ul>
     *
     * <p>The returned {@link Resolution#byBlock()} includes every block in {@code index}, including
     * blocks that ended up with no tags.
     *
     * <p>Tag references form a graph (tag -&gt; tag via {@code tags}/{@code tagPatterns}) that may
     * contain cycles, so resolution is Tarjan's strongly-connected-components algorithm: each tag is
     * visited exactly once, and every tag in the same cycle is resolved together, as one unit, to the
     * union of their own content plus whatever the cycle reaches from the outside. Resolving a whole
     * SCC as one value is what makes cyclical tags survive with real content instead of the old
     * permanent-null poisoning; visiting each node exactly once is what makes the reverse index
     * (byBlock) attribute correctly to the tag actually being resolved rather than to whichever tag an
     * enclosing loop happened to be sitting on when recursion happened.
     *
     * <p>The traversal is written iteratively (an explicit work stack standing in for the call stack)
     * rather than as a recursive helper. That is what keeps every piece of Tarjan bookkeeping a local
     * variable of this one method instead of fields on some shared resolver object — a resource pack
     * can define thousands of tags, and this way neither a deep tag chain nor a large graph can
     * overflow the JVM call stack.
     *
     * @param definitions already layered; see {@link RawTagDef#mergeUnder}
     * @param index       blocks known to exist, with any tags the game registry already assigns them
     */
    public static Resolution resolve(Map<Ident, RawTagDef> definitions, BlockIndex index) {
        List<Diagnostic> diagnostics = new ArrayList<>();

        // Every id that can legally be referenced — and, since a reference can never point outside
        // this set (see computeNeighborTags), also every tag id that will ever be visited.
        Set<Ident> candidateTags = new LinkedHashSet<>(definitions.keySet());
        candidateTags.addAll(index.tags());

        // Tarjan bookkeeping, all scoped to this one call.
        Map<Ident, Integer> discoveryIndex = new HashMap<>();
        Map<Ident, Integer> low = new HashMap<>();
        Set<Ident> onSccStack = new HashSet<>();
        Deque<Ident> sccStack = new ArrayDeque<>();
        int[] counter = {0};

        // A tag's own contribution (explicit blocks + pattern matches + whatever the index already
        // assigns it) and its outgoing edges (other tags it pulls in, self-references stripped),
        // each computed exactly once per tag, the moment it is first visited.
        Map<Ident, Set<Ident>> localBlocks = new HashMap<>();
        Map<Ident, Set<Ident>> neighborTags = new HashMap<>();
        // Final resolved content per tag, filled in once its whole SCC has been computed.
        Map<Ident, Set<Ident>> resolved = new HashMap<>();

        // The explicit stand-in for the recursive call stack: one frame per tag currently "being
        // visited", paired with an iterator over the neighbors still left to explore for that frame.
        Deque<Ident> callStack = new ArrayDeque<>();
        Deque<Iterator<Ident>> pendingNeighbors = new ArrayDeque<>();

        for (Ident start : candidateTags) {
            if (discoveryIndex.containsKey(start)) {
                continue;
            }
            visit(start, definitions, index, candidateTags, diagnostics, discoveryIndex, low,
                    onSccStack, sccStack, counter, localBlocks, neighborTags, callStack, pendingNeighbors);

            while (!callStack.isEmpty()) {
                Ident v = callStack.peek();
                Iterator<Ident> it = pendingNeighbors.peek();
                if (it.hasNext()) {
                    Ident w = it.next();
                    if (!discoveryIndex.containsKey(w)) {
                        visit(w, definitions, index, candidateTags, diagnostics, discoveryIndex, low,
                                onSccStack, sccStack, counter, localBlocks, neighborTags, callStack,
                                pendingNeighbors);
                    } else if (onSccStack.contains(w)) {
                        // Back edge to an ancestor still being resolved: part of the same cycle.
                        low.put(v, Math.min(low.get(v), discoveryIndex.get(w)));
                    }
                    // Otherwise w is already fully resolved; its value is sitting in `resolved` for
                    // the merge step below to pick up.
                    continue;
                }

                // v has no unexplored neighbors left: pop its frame and fold its low-link into its
                // caller's before deciding whether v closes off a strongly-connected component.
                callStack.pop();
                pendingNeighbors.pop();
                if (!callStack.isEmpty()) {
                    Ident parent = callStack.peek();
                    low.put(parent, Math.min(low.get(parent), low.get(v)));
                }

                if (low.get(v).equals(discoveryIndex.get(v))) {
                    closeStronglyConnectedComponent(
                            v, sccStack, onSccStack, localBlocks, neighborTags, resolved, diagnostics);
                }
            }
        }

        Map<Ident, Set<Ident>> byTag = new HashMap<>();
        for (Ident tag : candidateTags) {
            byTag.put(tag, resolved.getOrDefault(tag, Set.of()));
        }

        // Every block in the index must be present, even ones no tag ever claims.
        Map<Ident, Set<Ident>> byBlock = new HashMap<>();
        for (Ident block : index.blocks()) {
            byBlock.put(block, new HashSet<>());
        }
        for (var entry : byTag.entrySet()) {
            for (Ident block : entry.getValue()) {
                byBlock.computeIfAbsent(block, b -> new HashSet<>()).add(entry.getKey());
            }
        }

        return new Resolution(byTag, byBlock, diagnostics);
    }

    /** Opens a new Tarjan frame for {@code v}: assigns its indices and pushes its call/SCC frames. */
    private static void visit(
            Ident v,
            Map<Ident, RawTagDef> definitions,
            BlockIndex index,
            Set<Ident> candidateTags,
            List<Diagnostic> diagnostics,
            Map<Ident, Integer> discoveryIndex,
            Map<Ident, Integer> low,
            Set<Ident> onSccStack,
            Deque<Ident> sccStack,
            int[] counter,
            Map<Ident, Set<Ident>> localBlocks,
            Map<Ident, Set<Ident>> neighborTags,
            Deque<Ident> callStack,
            Deque<Iterator<Ident>> pendingNeighbors) {
        discoveryIndex.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        sccStack.push(v);
        onSccStack.add(v);

        Set<Ident> neighbors = computeNeighborTags(v, definitions, candidateTags, diagnostics);
        localBlocks.put(v, computeLocalBlocks(v, definitions, index, diagnostics));
        neighborTags.put(v, neighbors);

        callStack.push(v);
        pendingNeighbors.push(neighbors.iterator());
    }

    /**
     * {@code v} is the root of its strongly-connected component: every member is now known, so
     * resolve them together as one union rather than leaving any of them partial. A lone member here
     * means either an ordinary acyclic tag or one whose only self-reference was already filtered out
     * as the "extend yourself" idiom in {@link #computeNeighborTags} — neither is a real cycle, so
     * only a component with more than one member is reported as one.
     */
    private static void closeStronglyConnectedComponent(
            Ident root,
            Deque<Ident> sccStack,
            Set<Ident> onSccStack,
            Map<Ident, Set<Ident>> localBlocks,
            Map<Ident, Set<Ident>> neighborTags,
            Map<Ident, Set<Ident>> resolved,
            List<Diagnostic> diagnostics) {
        List<Ident> members = new ArrayList<>();
        Ident w;
        do {
            w = sccStack.pop();
            onSccStack.remove(w);
            members.add(w);
        } while (!w.equals(root));

        Set<Ident> memberSet = new HashSet<>(members);
        Set<Ident> merged = new HashSet<>();
        for (Ident m : members) {
            merged.addAll(localBlocks.get(m));
            for (Ident nb : neighborTags.get(m)) {
                // Content reached from inside the component is already folded in via the members'
                // own local contributions; only pull in what lies outside it.
                if (!memberSet.contains(nb)) {
                    merged.addAll(resolved.getOrDefault(nb, Set.of()));
                }
            }
        }
        for (Ident m : members) {
            resolved.put(m, merged);
        }

        if (members.size() > 1) {
            // `members` popped off the stack in reverse of discovery order; reverse it back so the
            // diagnostic reads as the actual traversal, and close the loop for clarity.
            List<Ident> path = new ArrayList<>(members);
            Collections.reverse(path);
            path.add(path.get(0));
            diagnostics.add(new Diagnostic.Cycle(List.copyOf(path)));
        }
    }

    private static Set<Ident> computeLocalBlocks(
            Ident tag, Map<Ident, RawTagDef> definitions, BlockIndex index, List<Diagnostic> diagnostics) {
        Set<Ident> blocks = new HashSet<>(index.blocksOf(tag));
        RawTagDef def = definitions.get(tag);
        if (def == null) {
            return blocks;
        }
        blocks.addAll(def.blocks());
        for (String patternSource : def.patterns()) {
            Pattern pattern = compile(tag, patternSource, diagnostics);
            if (pattern == null) {
                continue;
            }
            for (Ident block : index.blocks()) {
                if (pattern.matcher(block.toString()).find()) {
                    blocks.add(block);
                }
            }
        }
        return blocks;
    }

    private static Set<Ident> computeNeighborTags(
            Ident tag, Map<Ident, RawTagDef> definitions, Set<Ident> candidateTags, List<Diagnostic> diagnostics) {
        Set<Ident> refs = new LinkedHashSet<>();
        RawTagDef def = definitions.get(tag);
        if (def == null) {
            return refs;
        }
        for (Ident ref : def.tags()) {
            // Self-reference is the documented idiom for extending an existing tag — it adds no new
            // reachable content (the tag's own contribution is already accounted for) and is not a
            // cycle, so it is dropped silently rather than treated as an edge.
            if (ref.equals(tag)) {
                continue;
            }
            if (!candidateTags.contains(ref)) {
                diagnostics.add(new Diagnostic.MissingReference(tag, ref));
                continue;
            }
            refs.add(ref);
        }
        for (String patternSource : def.tagPatterns()) {
            Pattern pattern = compile(tag, patternSource, diagnostics);
            if (pattern == null) {
                continue;
            }
            for (Ident candidate : candidateTags) {
                if (candidate.equals(tag)) {
                    continue;
                }
                if (pattern.matcher(candidate.toString()).find()) {
                    refs.add(candidate);
                }
            }
        }
        return refs;
    }

    /** Compiles a pattern, reporting and returning null instead of letting the exception escape. */
    private static Pattern compile(Ident owner, String source, List<Diagnostic> diagnostics) {
        try {
            return Pattern.compile(source);
        } catch (PatternSyntaxException e) {
            diagnostics.add(new Diagnostic.BadPattern(owner, source, e.getMessage()));
            return null;
        }
    }
}
