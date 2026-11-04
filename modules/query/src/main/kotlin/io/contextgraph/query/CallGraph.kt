package io.contextgraph.query

import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphEdge
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.StorageAdapter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Path segments that mark a test source root. Compared lowercased. */
private val TEST_DIRECTORY_SEGMENTS = setOf(
    "test", "tests", "__tests__", "androidtest", "testfixtures", "unittest", "integrationtest", "spec", "specs"
)

/**
 * Whether [path] is test code: under a test source root (`src/test`, `__tests__`, ...) or named
 * like a test file (`FooTest.kt`, `foo.test.ts`, `foo_test.go`, `test_foo.py`).
 */
fun isTestSourcePath(path: String): Boolean {
    val segments = path.split('/')
    if (segments.dropLast(1).any { it.lowercase() in TEST_DIRECTORY_SEGMENTS }) return true
    val fileName = segments.last()
    val stem = fileName.substringBefore('.')
    return stem.endsWith("Test") || stem.endsWith("Tests") || stem.endsWith("Spec") ||
        stem.endsWith("_test") || stem.startsWith("test_") ||
        ".test." in fileName || ".spec." in fileName
}

/** How much further a goal-directed callees walk explores than it shows. */
private const val PRUNED_EXPLORATION_FACTOR = 20

/** The confidence below which a resolved call is a name-only guess rather than a resolution. */
const val RESOLVED_CALL_CONFIDENCE = 0.8

/**
 * One resolved call between two declarations: who calls, who is called, and where.
 *
 * [via] is set when the call reaches [callee] through a declaration it overrides or implements --
 * a call to `OrderRepository.load` counted as a call to `OrderRepositoryImpl.load` -- and names
 * that declaration, so a reader can tell a direct call from a dispatched one.
 */
data class CallSite(
    val caller: GraphNode,
    val callee: GraphNode,
    val path: String,
    val lines: List<Int>,
    val confidence: Double,
    val rung: String?,
    val via: GraphNode? = null
)

/**
 * The calls into or out of one declaration, split the way a reader has to weigh them: the
 * resolved ones, which are the answer, and counts of what was left out and why, so the answer
 * can say how complete it is instead of padding itself with guesses and tests.
 */
data class CallList(
    val target: GraphNode,
    val resolved: List<CallSite>,
    /** Calls at or above the threshold from test sources, left out unless asked for. */
    val testCalls: List<CallSite>,
    /** Calls below the threshold: name-only guesses, ambiguous matches. */
    val guesses: List<CallSite>,
    /** For a type: the types that extend or implement it. For a method: the methods that override it. */
    val implementations: List<GraphNode>,
    val minConfidence: Double
)

/** One node of a [CallGraph.trace] tree. */
data class TraceNode(
    val node: GraphNode,
    /** The call that led here from the parent, null for the root. */
    val call: CallSite?,
    val children: List<TraceNode>,
    /** Why the walk stopped here, null when it went on through [children]. */
    val stop: TraceStop?
)

enum class TraceStop {
    /** The node matched the entry-point pattern. */
    ENTRY_POINT,
    /** Nothing further in the walked direction. */
    LEAF,
    /** The depth limit was reached with more to follow. */
    DEPTH,
    /** Already shown elsewhere in the tree; its subtree is not repeated. */
    SEEN,
    /** The node budget ran out. */
    BUDGET
}

data class Trace(
    val root: TraceNode,
    val direction: Direction,
    val depth: Int,
    val minConfidence: Double,
    val nodesShown: Int,
    val truncated: Boolean
) {
    enum class Direction { CALLERS, CALLEES }
}

/**
 * Read-only queries over the resolved call graph -- `Calls` edges plus the type hierarchy pass 2
 * builds -- in the shapes the questions about it take: who calls this, what does this call, and
 * the same walked several hops out.
 *
 * Overrides are followed, because that is how calls dispatch. A caller of `OrderRepository.load`
 * reaches every `load` that overrides it, so the callers of an implementation include the callers
 * of the interface method it implements, and the callees of a call to an interface method include
 * its implementations. Each such call is marked with the declaration it went through.
 */
class CallGraph(private val storage: StorageAdapter) {

    /**
     * Who calls [id], directly or through a declaration [id] overrides. For a type, who calls it
     * (a constructor call) or any of its members -- what has to change if the type does.
     */
    fun callers(id: NodeId, minConfidence: Double = RESOLVED_CALL_CONFIDENCE, includeTests: Boolean = false): CallList? {
        val target = storage.getNode(id) ?: return null
        val calls = (listOf(target) + callableMembers(target)).flatMap { member ->
            val direct = storage.getEdgesTo(member.id).filter { it.type == EdgeType.Calls }.mapNotNull { callSite(it, viaFor = null) }
            val inherited = overridden(member).flatMap { declared ->
                storage.getEdgesTo(declared.id).filter { it.type == EdgeType.Calls }
                    .mapNotNull { callSite(it, viaFor = declared)?.copy(callee = member) }
            }
            direct + inherited
        }.filter { it.caller.id !in memberIds(target) }
        return split(target, calls, minConfidence, includeTests, implementations(target))
    }

    /**
     * What [id] calls, plus the implementations of any interface member it calls. For a type,
     * what its members call outside the type.
     */
    fun callees(id: NodeId, minConfidence: Double = RESOLVED_CALL_CONFIDENCE, includeTests: Boolean = false): CallList? {
        val source = storage.getNode(id) ?: return null
        val own = memberIds(source)
        val direct = (listOf(source) + callableMembers(source)).flatMap { member ->
            storage.getEdgesFrom(member.id).filter { it.type == EdgeType.Calls }.mapNotNull { callSite(it, viaFor = null) }
        }.filter { it.callee.id !in own }
        val dispatched = direct.flatMap { call ->
            implementations(call.callee).map { impl -> call.copy(callee = impl, via = call.callee, path = call.path) }
        }
        return split(source, direct + dispatched, minConfidence, includeTests, implementations(source), byCallee = true)
    }

    /**
     * [callers] or [callees] walked up to [depth] hops, as a tree rooted at [id].
     *
     * The walk stops at a node matching [entryPoint] (tested against the node's label and its
     * declaring type's name), at a node with nothing further, at [depth], or when [maxNodes] have
     * been shown. A node already in the tree is shown again where it recurs, marked, but its
     * subtree is not repeated -- so a diamond costs one line, not a copy of everything below it.
     *
     * Walking callees towards an [entryPoint] -- "which endpoints can this reach" -- keeps only
     * the branches that reach one. Everything a ViewModel calls fans out into formatting, state
     * and UI helpers; shown whole, that buried the four endpoint paths under hundreds of lines
     * and ran out of budget before reaching them. Callers are shown whole, because a branch that
     * ends with no caller is itself part of the answer.
     */
    fun trace(
        id: NodeId,
        direction: Trace.Direction,
        depth: Int,
        entryPoint: Regex?,
        minConfidence: Double = RESOLVED_CALL_CONFIDENCE,
        includeTests: Boolean = false,
        maxNodes: Int = 150
    ): Trace? {
        val root = storage.getNode(id) ?: return null
        val seen = HashSet<NodeId>().apply { add(root.id) }
        val pruneToGoal = direction == Trace.Direction.CALLEES && entryPoint != null
        // Pruning discards most of what it explores, so it explores further than it shows.
        val budget = if (pruneToGoal) maxNodes * PRUNED_EXPLORATION_FACTOR else maxNodes
        var shown = 1
        var truncated = false

        fun next(node: GraphNode): List<CallSite> {
            val list = when (direction) {
                Trace.Direction.CALLERS -> callers(node.id, minConfidence, includeTests)
                Trace.Direction.CALLEES -> callees(node.id, minConfidence, includeTests)
            } ?: return emptyList()
            return list.resolved.distinctBy { if (direction == Trace.Direction.CALLERS) it.caller.id else it.callee.id }
        }

        fun build(node: GraphNode, call: CallSite?, level: Int): TraceNode {
            if (level > 0 && entryPoint != null && matches(node, entryPoint)) return TraceNode(node, call, emptyList(), TraceStop.ENTRY_POINT)
            val calls = next(node)
            if (calls.isEmpty()) return TraceNode(node, call, emptyList(), TraceStop.LEAF)
            if (level >= depth) {
                truncated = true
                return TraceNode(node, call, emptyList(), TraceStop.DEPTH)
            }
            val children = mutableListOf<TraceNode>()
            for (c in calls) {
                val other = if (direction == Trace.Direction.CALLERS) c.caller else c.callee
                if (shown >= budget) {
                    truncated = true
                    return TraceNode(node, call, children, TraceStop.BUDGET)
                }
                shown++
                if (!seen.add(other.id)) {
                    children += TraceNode(other, c, emptyList(), TraceStop.SEEN)
                    continue
                }
                children += build(other, c, level + 1)
            }
            return TraceNode(node, call, children, null)
        }

        var tree = build(root, null, 0)
        if (pruneToGoal) {
            tree = pruneToEntryPoints(tree) ?: tree.copy(children = emptyList())
            shown = count(tree)
        }
        return Trace(tree, direction, depth, minConfidence, shown, truncated)
    }

    /** [node] with only the branches that reach an entry point, or null when none does. */
    private fun pruneToEntryPoints(node: TraceNode): TraceNode? {
        if (node.stop == TraceStop.ENTRY_POINT) return node
        val kept = node.children.mapNotNull { pruneToEntryPoints(it) }
        return if (kept.isEmpty()) null else node.copy(children = kept, stop = null)
    }

    private fun count(node: TraceNode): Int = 1 + node.children.sumOf { count(it) }

    /** The simple name of the type [node] is declared in, read out of its id, or null at file level. */
    fun declaringTypeOf(node: GraphNode): String? {
        val scope = node.id.value.substringAfter('#', "").substringBefore('(')
        val segments = scope.split('.')
        return if (segments.size >= 2) segments[segments.size - 2].ifEmpty { null } else null
    }

    private fun matches(node: GraphNode, entryPoint: Regex): Boolean =
        entryPoint.containsMatchIn(node.label) || declaringTypeOf(node)?.let(entryPoint::containsMatchIn) == true

    private fun split(
        target: GraphNode,
        calls: List<CallSite>,
        minConfidence: Double,
        includeTests: Boolean,
        implementations: List<GraphNode>,
        byCallee: Boolean = false
    ): CallList {
        val unique = calls
            .groupBy { (if (byCallee) it.callee.id else it.caller.id) to it.via?.id }
            .map { (_, sites) -> sites.maxBy { it.confidence } }
            .sortedWith(compareBy<CallSite> { it.path }.thenBy { it.lines.firstOrNull() ?: 0 })
        val (strong, weak) = unique.partition { it.confidence >= minConfidence }
        val (tests, production) = strong.partition { isTestSourcePath(if (byCallee) it.callee.id.value.substringBefore('#') else it.path) }
        return CallList(
            target = target,
            resolved = if (includeTests) strong else production,
            testCalls = if (includeTests) emptyList() else tests,
            guesses = weak,
            implementations = implementations,
            minConfidence = minConfidence
        )
    }

    private fun callSite(edge: GraphEdge, viaFor: GraphNode?): CallSite? {
        val caller = storage.getNode(edge.source) ?: return null
        val callee = storage.getNode(edge.target) ?: return null
        val lines = (edge.properties["lines"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }.orEmpty()
        return CallSite(
            caller = caller,
            callee = callee,
            path = edge.source.value.substringBefore('#'),
            lines = lines,
            confidence = edge.confidence,
            rung = (edge.properties["rung"] as? JsonPrimitive)?.content,
            via = viaFor
        )
    }

    /** A type's own methods and functions, through its `Contains` edges; empty for anything that is not a type. */
    private fun callableMembers(node: GraphNode): List<GraphNode> =
        storage.getEdgesFrom(node.id).filter { it.type == EdgeType.Contains }
            .mapNotNull { storage.getNode(it.target) }
            .filter { it.type == NodeType.Method || it.type == NodeType.Function }

    private fun memberIds(node: GraphNode): Set<NodeId> = callableMembers(node).mapTo(HashSet()) { it.id } + node.id

    /** The type declaring [member], through its `Contains` edge. */
    private fun ownerOf(member: GraphNode): GraphNode? =
        storage.getEdgesTo(member.id).firstOrNull { it.type == EdgeType.Contains }?.let { storage.getNode(it.source) }

    private fun membersNamed(type: GraphNode, label: String): List<GraphNode> =
        storage.getEdgesFrom(type.id).filter { it.type == EdgeType.Contains }
            .mapNotNull { storage.getNode(it.target) }
            .filter { it.label == label && it.id != type.id }

    /** The declarations [member] overrides: same-named members of its declaring type's supertypes, transitively. */
    private fun overridden(member: GraphNode): List<GraphNode> {
        val owner = ownerOf(member) ?: return emptyList()
        return walkTypes(owner) { storage.getEdgesFrom(it.id).filter { e -> e.type == EdgeType.Implements }.map { e -> e.target } }
            .flatMap { membersNamed(it, member.label) }
    }

    /**
     * For a type, the types that extend or implement it, transitively. For a member, the members
     * that override it.
     */
    private fun implementations(node: GraphNode): List<GraphNode> {
        val subtypesOf: (GraphNode) -> List<NodeId> = { storage.getEdgesTo(it.id).filter { e -> e.type == EdgeType.Implements }.map { e -> e.source } }
        if (storage.getEdgesTo(node.id).any { it.type == EdgeType.Implements }) return walkTypes(node, subtypesOf)
        val owner = ownerOf(node) ?: return emptyList()
        return walkTypes(owner, subtypesOf).flatMap { membersNamed(it, node.label) }
    }

    /** The types reachable from [start] by [step], excluding [start] itself. */
    private fun walkTypes(start: GraphNode, step: (GraphNode) -> List<NodeId>): List<GraphNode> {
        val out = LinkedHashMap<NodeId, GraphNode>()
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            for (id in step(next)) {
                if (id == start.id || id in out) continue
                val node = storage.getNode(id) ?: continue
                out[id] = node
                queue.addLast(node)
            }
        }
        return out.values.toList()
    }
}
