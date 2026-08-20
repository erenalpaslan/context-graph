package io.contextgraph.benchmark.retrieval

import io.contextgraph.query.QueryEngine

/**
 * The ContextGraph side of the retrieval measurement (AC-24): runs a question's raw text through
 * [QueryEngine.buildContext] -- deliberately this call and not [QueryEngine.search] or any other
 * surface method. The spec's own notes name `buildContext` as "the one closest to what an agent
 * would call" (it is the same call [io.contextgraph.benchmark.runner.ContextGraphMcpToolBridge]
 * makes for the agent-A/B axis's WITH_TOOLS arm, and the same operation the MCP server's
 * `build_context` tool exposes) and ask that whichever surface method is chosen be named and
 * justified, since the choice affects the result. This is that choice, made once, here.
 *
 * The ranked file list is [io.contextgraph.query.ContextBundle.evidence]'s paths, de-duplicated
 * in order: [io.contextgraph.query.ContextBundler.bundle] already ranks nodes by
 * `pageRank * confidence` descending before flattening them into evidence, so evidence order
 * *is* ContextGraph's own rank order -- this class does not re-rank anything, it only projects
 * `Provenance.path` out and removes repeats (the same file can appear as evidence for more than
 * one node).
 */
class ContextGraphRetrievalRunner(private val queryEngine: QueryEngine) {

    fun rankedFiles(questionText: String): List<String> =
        queryEngine.buildContext(questionText).evidence.map { it.path }.distinct()
}
