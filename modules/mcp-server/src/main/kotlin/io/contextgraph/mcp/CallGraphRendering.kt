package io.contextgraph.mcp

import io.contextgraph.core.GraphNode
import io.contextgraph.core.StorageAdapter
import io.contextgraph.query.CallList
import io.contextgraph.query.CallSite
import io.contextgraph.query.Trace
import io.contextgraph.query.TraceNode
import io.contextgraph.query.TraceStop

/**
 * Text for the `impact_analysis` and `trace` tools.
 *
 * Written for an agent deciding whether it can stop looking. A list that mixes containment,
 * tests and name-only guesses into "25 dependents" makes it re-derive the answer with a text
 * search, which is what a benchmarked agent did every time. So each answer states how complete it
 * is, lists only resolved calls with the `path:line` an agent would open, and counts everything
 * it left out with the argument that would list it.
 */
internal class CallGraphRendering(private val storage: StorageAdapter) {

    /** `OrderRepository.load` for `path#OrderRepository.load(String)`; the label at file level. */
    fun memberName(node: GraphNode): String {
        val scope = node.id.value.substringAfter('#', "").substringBefore('(')
        val segments = scope.split('.').filter { it.isNotEmpty() }
        return if (segments.size >= 2) segments.takeLast(2).joinToString(".") else node.label
    }

    fun declaredAt(node: GraphNode): String {
        val p = storage.getProvenance(node.id.value).firstOrNull()
        return when {
            p == null -> node.id.value.substringBefore('#')
            p.lineStart != null -> "${p.path}:${p.lineStart}"
            else -> p.path
        }
    }

    private fun callAt(site: CallSite): String {
        val first = site.lines.firstOrNull() ?: return site.path
        val more = site.lines.size - 1
        return "${site.path}:$first" + if (more > 0) " (+$more more call${if (more == 1) "" else "s"} in this method)" else ""
    }

    private fun strength(site: CallSite): String =
        listOfNotNull(site.rung, "%.2f".format(site.confidence)).joinToString(" ")

    fun callers(list: CallList, limit: Int): String = render(list, limit, incoming = true)

    fun callees(list: CallList, limit: Int): String = render(list, limit, incoming = false)

    private fun render(list: CallList, limit: Int, incoming: Boolean): String = buildString {
        val target = list.target
        val noun = if (incoming) "Callers of" else "Calls from"
        appendLine("$noun ${memberName(target)} -- declared at ${declaredAt(target)}")
        val shown = list.resolved.take(limit)
        appendLine(
            "${list.resolved.size} resolved call${if (list.resolved.size == 1) "" else "s"} at confidence >= ${list.minConfidence} " +
                "from production code. Complete for every call the graph resolved; a call it could not resolve " +
                "(reflection, a function passed as a value, an untyped receiver) is not listed."
        )
        for (site in shown) {
            val other = if (incoming) site.caller else site.callee
            val where = if (incoming) callAt(site) else "called at ${callAt(site)}, declared at ${declaredAt(other)}"
            val via = site.via?.let { " (via ${memberName(it)})" }.orEmpty()
            appendLine("- ${memberName(other)}$via  $where  [${strength(site)}]")
        }
        if (list.resolved.size > shown.size) {
            appendLine("... ${list.resolved.size - shown.size} more not shown (showing ${shown.size}) -- raise 'limit'. This list is INCOMPLETE.")
        }
        val omitted = buildList {
            if (list.testCalls.isNotEmpty()) add("${list.testCalls.size} from test code (includeTests=true lists them)")
            if (list.guesses.isNotEmpty()) {
                add("${list.guesses.size} below ${list.minConfidence}, name-only guesses (minConfidence=0.4 lists them)")
            }
        }
        if (omitted.isNotEmpty()) appendLine("Not listed: ${omitted.joinToString("; ")}.")
        if (list.implementations.isNotEmpty()) {
            val names = list.implementations.take(10).joinToString(", ") { memberName(it) }
            val more = list.implementations.size - 10
            appendLine(
                "Overridden or implemented by: $names${if (more > 0) " and $more more" else ""}" +
                    if (incoming) " -- their own callers are not in this list unless they call through ${memberName(target)}." else "."
            )
        }
        append("id: ${target.id.value}")
    }

    fun trace(trace: Trace, entryPoints: Regex?): String = buildString {
        val callers = trace.direction == Trace.Direction.CALLERS
        val root = trace.root
        append(if (callers) "Callers of " else "Calls from ")
        append("${memberName(root.node)} (${declaredAt(root.node)}), up to ${trace.depth} hops, resolved calls (>= ${trace.minConfidence})")
        if (entryPoints != null) append(", stopping at entry points matching /${entryPoints.pattern}/")
        appendLine(":")

        val entries = mutableListOf<TraceNode>()
        val leaves = mutableListOf<TraceNode>()
        val cut = mutableListOf<TraceNode>()
        val arrow = if (callers) "<-" else "->"

        fun line(node: TraceNode, indent: Int) {
            val call = node.call
            val where = when {
                call == null -> ""
                callers -> "  ${callAt(call)}"
                else -> "  ${declaredAt(node.node)}"
            }
            val via = call?.via?.let { " (via ${memberName(it)})" }.orEmpty()
            val mark = when (node.stop) {
                TraceStop.ENTRY_POINT -> "  [entry point]"
                TraceStop.LEAF -> if (callers) "  [nothing calls this]" else "  [calls nothing further]"
                TraceStop.DEPTH -> "  [depth limit: more beyond]"
                TraceStop.SEEN -> "  [shown above]"
                TraceStop.BUDGET -> "  [node budget reached]"
                null -> ""
            }
            val prefix = if (indent == 0) "" else "  ".repeat(indent - 1) + "$arrow "
            appendLine("$prefix${memberName(node.node)}$via$where$mark")
            when (node.stop) {
                TraceStop.ENTRY_POINT -> entries += node
                TraceStop.LEAF -> if (indent > 0) leaves += node
                TraceStop.DEPTH, TraceStop.BUDGET -> cut += node
                else -> Unit
            }
            node.children.forEach { line(it, indent + 1) }
        }
        line(root, 0)

        if (entryPoints != null) {
            appendLine()
            appendLine("Entry points reached (${entries.size}): ${entries.distinctBy { it.node.id }.joinToString(", ") { memberName(it.node) }.ifEmpty { "none" }}")
        }
        if (leaves.isNotEmpty()) {
            val names = leaves.distinctBy { it.node.id }.joinToString(", ") { memberName(it.node) }
            appendLine(
                if (callers) {
                    "Ends with no caller in the graph (${leaves.size}): $names -- reached some other way " +
                        "(dependency injection, a framework, reflection) or not at all."
                } else {
                    "Ends with no further calls (${leaves.size}): $names"
                }
            )
        }
        if (cut.isNotEmpty()) appendLine("Not followed further: ${cut.size} branch${if (cut.size == 1) "" else "es"} at the depth limit or node budget; raise 'depth' or trace from them.")
        appendLine(
            "Followed: resolved calls and calls dispatched through the declarations a method overrides. Not followed: calls the graph " +
                "could not resolve, and wiring that is not a call (dependency-injection multibindings, reflection)."
        )
        append("id: ${root.node.id.value}")
    }
}
