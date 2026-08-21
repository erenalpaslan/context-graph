package io.contextgraph.ingest

import io.contextgraph.core.ConfidenceDefaults
import io.contextgraph.core.EdgeId
import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.StorageAdapter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * What a call reference is allowed to resolve to.
 *
 * Every [io.contextgraph.core.UnresolvedReference] comes from a call site -- `method_invocation`
 * in the Java grammar and its equivalent in each of the others -- so a field can never be one's
 * target, however exactly its name matches. Filtering the candidate set to callables is not
 * tidiness: candidates are counted against
 * [ConfidenceDefaults.CALL_RESOLUTION_CANDIDATE_CAP] *before* anything is emitted, so a
 * non-callable padding the count pushes a perfectly unambiguous call over the cap and the
 * reference resolves to nothing at all.
 *
 * That is not hypothetical. In Keycloak, `.name("executor-task-timeout")` on a config builder
 * collected four same-named candidates -- `ComponentModel.name`, `ProviderConfigProperty.name`,
 * `ProviderConfigPropertyBuilder.name`, and `ProviderConfigPropertyBuilder.name(String)`. Three
 * are fields. One is the answer. Four exceeds the cap of three, so the call produced no edge,
 * while `.type(...)` and `.helpText(...)` two lines away sat at exactly three and resolved --
 * making the loss look arbitrary rather than mechanical. Callable-only leaves one candidate:
 * unambiguous, top confidence, nowhere near the cap.
 *
 * [NodeType.Class] is included because several grammars emit a constructor call as a reference
 * to the type's own name; excluding it would trade this bug for its mirror image.
 */
private val CALLABLE_TYPES = setOf<NodeType>(NodeType.Method, NodeType.Function, NodeType.Class)

/**
 * Pass 2 of indexing: resolves every [io.contextgraph.core.UnresolvedReference] pass 1 has
 * ever persisted -- not just the ones touched by this run's pass 1 -- against the current
 * declaration symbol table, and materialises the result as `Calls` edges.
 *
 * Naive and full, deliberately (see slice 09's notes: the largest target repo is under 5k
 * files, so re-resolving everything on every run is affordable and obviously correct;
 * scoped invalidation is an explicit non-goal here). Every call to [resolveAll] wipes the
 * *entire* `Calls` edge set and rebuilds it from scratch by matching
 * [io.contextgraph.core.UnresolvedReference.referenceName] against [io.contextgraph.core.GraphNode.label]
 * across every declaration currently in [storage].
 *
 * This full-rebuild-from-persisted-state design is what makes the two hard incremental
 * requirements fall out for free, with no scoping logic of their own:
 *
 *  - **A reference in an unchanged file resolves against a just-edited file.** Pass 1 only
 *    deletes and re-emits a file's own unresolved references when that file is actually
 *    reprocessed (checksum changed). A reference belonging to a skipped file is therefore
 *    still sitting in [StorageAdapter.getAllUnresolvedReferences] this call, and any
 *    declaration a different, just-reindexed file added is already visible through
 *    [StorageAdapter.findNodesByLabel] by the time this runs -- so resolution picks up the
 *    new match without the unchanged file being touched at all.
 *  - **No edge is left pointing at a node that no longer exists.** Because the entire
 *    `Calls` set is deleted and rebuilt from the *current* symbol table every time, a
 *    reference whose target was renamed or removed simply fails to resolve again instead of
 *    leaving a stale edge behind. (Structural edges another artifact holds into a file being
 *    reindexed are a separate concern, handled by `SqliteStorageAdapter.deleteNodesForArtifact`
 *    excluding `Calls` from its own per-node cleanup -- this class owns rebuilding the set
 *    that decision depends on existing.)
 *
 * Resolution is graded, not just unambiguous-or-nothing: [ResolutionLadder] (slice 10) picks
 * the most precise rung -- local scope, file imports, same directory, repo-wide unique name
 * -- that yields any same-name candidates, and this class emits an edge to each candidate at
 * that rung, at its confidence, provided there are no more than
 * [ConfidenceDefaults.CALL_RESOLUTION_CANDIDATE_CAP] of them; more than that and the
 * reference stays unresolved rather than guessing among a hairball of same-named
 * declarations.
 *
 * Must run strictly after pass 1's write phase has fully drained -- never concurrently with
 * it -- so the single-writer property [IngestPipeline] depends on for SQLite lock avoidance
 * is preserved. [IngestPipeline.index] enforces this by calling [resolveAll] only after its
 * producer/consumer `coroutineScope` has returned.
 */
class ReferenceResolver(private val storage: StorageAdapter) {

    fun resolveAll(): ResolutionStats {
        storage.deleteEdgesOfType(EdgeType.Calls)
        storage.deleteEdgesOfType(EdgeType.Implements)

        val hierarchy = TypeHierarchy.from(storage)
        hierarchy.emitImplementsEdges(storage)

        val references = storage.getAllUnresolvedReferences()
        var resolved = 0
        var unresolved = 0

        // Keyed by reference name, because references repeat far more than names do: half a
        // million call sites in a real repo draw on a few tens of thousands of distinct names, so
        // looking each one up per *reference* meant half a million SQL round-trips for the same
        // few answers, and re-parsing every candidate's declaring type out of its id each time.
        // One entry per name, built on first sight, does both jobs once.
        val byName = HashMap<String, Candidates>()

        fun candidatesFor(name: String) =
            byName.getOrPut(name) { Candidates.of(storage.findNodesByLabel(name)) }

        // Accumulated rather than written as we go, because one (caller, callee) pair can have
        // many call sites and they must arrive as one edge carrying every line, not as the last
        // one silently overwriting the rest.
        val pending = LinkedHashMap<Pair<NodeId, NodeId>, CallEdge>()

        for (reference in references) {
            // A chained receiver has no declared type in the calling file, only the name of the
            // call that produced it. That name is resolvable here, where the whole symbol table
            // exists, and the method it names carries its own declared return type.
            val receiverType = reference.receiverType
                ?: reference.receiverCall?.let { candidatesFor(it).commonReturnType(hierarchy) }
            val candidates = candidatesFor(reference.referenceName).forReceiver(receiverType)
            val match = ResolutionLadder.resolve(reference, candidates, storage)

            if (match == null || match.second.size > ConfidenceDefaults.CALL_RESOLUTION_CANDIDATE_CAP) {
                unresolved++
                continue
            }

            val (rung, rungCandidates) = match
            val confidence = rung.confidenceFor(ambiguous = rungCandidates.size > 1)

            for (target in rungCandidates) {
                pending.getOrPut(reference.referringSymbolId to target.id) {
                    CallEdge(rung.label, confidence)
                }.lines.add(reference.line)
            }
            resolved++
        }

        pending.forEach { (pair, edge) ->
            val (source, target) = pair
            storage.upsertEdge(
                GraphEdge(
                    id = EdgeId("calls:${source.value}:${target.value}"),
                    source = source,
                    target = target,
                    type = EdgeType.Calls,
                    confidence = edge.confidence,
                    properties = mapOf(
                        "rung" to JsonPrimitive(edge.rung),
                        // The call-site lines themselves, which is what a question of the form
                        // "list every call site as file:line" actually needs. Without them the
                        // graph names the calling *method* and a reader has to open the file and
                        // search it -- the step that made a benchmarked agent read 27 files after
                        // the graph had already handed it the right answer.
                        "lines" to JsonArray(edge.lines.sorted().map { JsonPrimitive(it) })
                    )
                )
            )
        }

        logger.debug {
            "Pass 2: resolved $resolved/${references.size} unresolved references into Calls " +
                "edges ($unresolved left unresolved: no candidates anywhere, or more than " +
                "${ConfidenceDefaults.CALL_RESOLUTION_CANDIDATE_CAP} at the rung that matched)"
        }
        return ResolutionStats(resolved = resolved, unresolved = unresolved)
    }

}

/** One (caller, callee) pair's accumulating call sites, before it becomes a single edge. */
private class CallEdge(val rung: String, val confidence: Double) {
    val lines = sortedSetOf<Int>()
}

/**
 * Which types extend or implement which, by simple name, read off the declarations pass 1 wrote.
 *
 * Names, not declaration sites, because that is what a supertype is at its use site: a class says
 * `implements UserSessionModel` and nothing in that file says which file declares it. Two distinct
 * types sharing a simple name will therefore merge here -- accepted deliberately, since the only
 * consumers are a supertype test that falls back to "no answer" and an `Implements` edge that is
 * itself name-resolved.
 */
private class TypeHierarchy(private val directSupertypes: Map<String, Set<String>>) {

    /** Whether [sub] reaches [candidate] by extends/implements, at any depth. [sub] counts as its own. */
    fun isSubtypeOf(sub: String, candidate: String): Boolean {
        if (sub == candidate) return true
        val seen = HashSet<String>()
        val queue = ArrayDeque(directSupertypes[sub].orEmpty())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next == candidate) return true
            // Java forbids cycles here, but a graph assembled from separately-parsed files is not
            // guaranteed to be a Java program -- two files can each claim to extend the other.
            if (seen.add(next)) queue.addAll(directSupertypes[next].orEmpty())
        }
        return false
    }

    fun emitImplementsEdges(storage: StorageAdapter) {
        typeNodesByName.forEach { (_, subNodes) ->
            subNodes.forEach { sub ->
                supertypeNamesOf(sub).forEach { superName ->
                    typeNodesByName[superName].orEmpty().forEach { superNode ->
                        storage.upsertEdge(
                            GraphEdge(
                                id = EdgeId("implements:${sub.id.value}:${superNode.id.value}"),
                                source = sub.id,
                                target = superNode.id,
                                type = EdgeType.Implements,
                                confidence = ConfidenceDefaults.AST_SYMBOL
                            )
                        )
                    }
                }
            }
        }
    }

    private lateinit var typeNodesByName: Map<String, List<GraphNode>>

    companion object {
        private val TYPE_NODES = setOf<NodeType>(
            NodeType.Class, NodeType.Custom("Interface"), NodeType.Custom("Enum"), NodeType.Custom("Record")
        )

        fun from(storage: StorageAdapter): TypeHierarchy {
            val types = storage.getAllNodes().filter { it.type in TYPE_NODES }
            val direct = HashMap<String, MutableSet<String>>()
            types.forEach { t -> direct.getOrPut(t.label) { HashSet() }.addAll(supertypeNamesOf(t)) }
            return TypeHierarchy(direct).also { it.typeNodesByName = types.groupBy { n -> n.label } }
        }

        fun supertypeNamesOf(node: GraphNode): List<String> =
            (node.properties["supertypes"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty()
    }
}

/**
 * Every declaration sharing one name, indexed the two ways resolution asks for them.
 *
 * Built once per distinct reference name and reused across every call site that mentions it.
 * Both fields are derived from the same node list, so the grouping can never disagree with the
 * whole.
 */
private class Candidates(
    /** All callable declarations under this name -- the answer when there is no usable hint. */
    private val all: List<GraphNode>,
    private val byDeclaringType: Map<String?, List<GraphNode>>
) {
    /**
     * The candidates declared on [receiverType], if any are; otherwise all of them.
     *
     * The receiver's declared type is the one input that can separate 1001 same-named `getId`
     * declarations, and it separates them *before*
     * [ConfidenceDefaults.CALL_RESOLUTION_CANDIDATE_CAP] counts them -- which is the whole point.
     * Those calls were not resolving ambiguously; they were clearing the cap and resolving to
     * nothing at all.
     *
     * Narrowing only, never losing: a null hint, or one matching no candidate, yields the full
     * set. That second case is real and common -- the hint is the receiver's *static* type, so a
     * call to a method the declared type inherits rather than declares finds nothing under that
     * type's name. Falling back leaves such a call exactly where it was before the hint existed,
     * so the hint's failures cost nothing and its successes are free.
     */
    fun forReceiver(receiverType: String?): List<GraphNode> {
        if (receiverType == null || all.size <= 1) return all
        return byDeclaringType[receiverType] ?: all
    }

    /**
     * The declared return type every declaration under this name agrees on, or null.
     *
     * Used to type a chained receiver: `getParentSession()` returning
     * `RootAuthenticationSessionModel` everywhere makes that the receiver type of the next call in
     * the chain.
     *
     * Agreement is not identity. `getUserSession` is declared 21 times in Keycloak, returning
     * `UserSessionModel` 18 times and `UserSessionAdapter`/`UserSessionUpdater` once or twice --
     * and those two implement the first. Whichever one the chain went through, the call that
     * follows reaches the method `UserSessionModel` declares, so the supertype is a sound answer
     * for all 21. Requiring identity here left those call sites unresolved.
     *
     * Soundness comes from *supertype*, never from *majority*: the type returned must be one
     * every other returned type is a subtype of, so the narrowing it drives is true whichever
     * declaration the call actually reached. A plain popularity contest would push a wrong
     * candidate set past the cap and turn calls that resolve today into calls that resolve
     * wrongly. Types with no common supertype in the graph still yield null.
     */
    fun commonReturnType(hierarchy: TypeHierarchy): String? {
        val returns = all.mapNotNull { (it.properties["returns"] as? JsonPrimitive)?.content }
        if (returns.isEmpty() || returns.size != all.size) return null
        val distinct = returns.distinct()
        distinct.singleOrNull()?.let { return it }
        return distinct.firstOrNull { candidate -> distinct.all { hierarchy.isSubtypeOf(it, candidate) } }
    }

    companion object {
        fun of(sameNameNodes: List<GraphNode>): Candidates {
            val callable = sameNameNodes.filter { it.type in CALLABLE_TYPES }
            return Candidates(callable, callable.groupBy { declaringTypeOf(it.id) })
        }

        /**
         * The simple name of the type a declaration sits in, read out of its id.
         *
         * Ids are declaration sites (`repoRelativePath#Outer.Inner.method(Params)`, see
         * `io.contextgraph.treesitter.DeclarationSiteId`), so the declaring type is the scope
         * segment before the member's own. Read from the id rather than followed through a
         * `contains` edge because the id already carries the answer.
         */
        private fun declaringTypeOf(id: NodeId): String? {
            val scope = id.value.substringAfter('#', "")
            if (scope.isEmpty()) return null
            // The parameter list goes first: a qualified parameter type (`save(java.util.List)`)
            // puts dots inside the member's own segment, and splitting before removing them would
            // mistake `util` for the declaring type.
            val segments = scope.substringBefore('(').split('.')
            return if (segments.size >= 2) segments[segments.size - 2].ifEmpty { null } else null
        }
    }
}

data class ResolutionStats(val resolved: Int, val unresolved: Int)
