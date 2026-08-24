package io.contextgraph.query

import io.contextgraph.core.GraphNode
import io.contextgraph.core.Provenance
import io.contextgraph.core.StorageAdapter
import io.contextgraph.graph.GraphAlgorithms

class ContextBundler(
    private val storage: StorageAdapter,
    private val algorithms: GraphAlgorithms = GraphAlgorithms()
) {
    /**
     * [relevance] is how the query reaches the ordering. Null -- what a pure graph walk passes,
     * having no query to be relevant to -- ranks by `pageRank * confidence` alone, exactly as this
     * did before ranking became query-aware.
     */
    fun bundle(
        nodes: List<GraphNode>,
        maxNodes: Int = DEFAULT_MAX_NODES,
        relevance: QueryRelevance? = null
    ): ContextBundle {
        if (nodes.isEmpty()) return ContextBundle(emptyList(), emptyList(), emptyList(), emptyMap())

        val nodeIds = nodes.map { it.id }.toSet()

        // Collect all edges between the result nodes
        val edges = nodes.flatMap { node ->
            storage.getEdgesFrom(node.id).filter { it.target in nodeIds } +
            storage.getEdgesTo(node.id).filter { it.source in nodeIds }
        }.distinctBy { it.id }

        // PageRank on the subgraph to rank nodes
        val g = algorithms.buildJGraphT(nodes, edges)
        val ranks = algorithms.pageRank(g)

        // Fetched for every candidate rather than for the survivors, because two ranking signals
        // (where the query's words fall in a file's path, and whether that file is documentation
        // or a test) need a candidate's file *before* the cut that decides which candidates
        // survive. One batched lookup, not one query per node; the evidence below re-uses it.
        // Sorted on arrival, once, because three things downstream read these rows and all three
        // are published: the score below picks a node's representative path from the front of its
        // list, the evidence list is flattened from them in order, and callers read their whole
        // ranked file list off that evidence. `getProvenanceFor` issues no ORDER BY, so without
        // this each node's rows arrive in SQLite row order, which differs between index builds
        // because ingest extracts concurrently. Nodes with more than one distinct path are not
        // rare -- 409 of them in the calcom index, 43 in keycloak, 31 in excalidraw.
        val provenance = storage.getProvenanceFor(nodes.map { it.id.value })
            .mapValues { (_, rows) -> rows.sortedWith(PROVENANCE_ORDER) }

        // Scored once per candidate, then sorted. Scoring inside the comparator instead would look
        // tidier and re-derive every score on every comparison -- sortedByDescending calls its
        // selector per comparison, not per element, so a path would be split into words O(n log n)
        // times. Invisible on a small graph and real on a large one.
        val scores = nodes.associate { node ->
            val queryScore = relevance?.score(node, provenance[node.id.value]?.firstOrNull()?.path) ?: 0.0
            node.id to queryScore + (ranks[node.id] ?: 0.0) * node.confidence
        }

        // `thenBy { it.id.value }` is what makes this a total order, and it is load-bearing rather
        // than tidy. Scores here are sums of a few coarse constants over a PageRank that is flat
        // across unlinked candidates, so exact ties are routine, not exotic. A stable sort leaves
        // tied nodes in the order `nodes` arrived in -- SQLite row order again -- and `take` below
        // then cuts on that accident, which decides not merely the order of what survives but what
        // survives. The id comparison runs only when the scores are equal, so the cost of removing
        // the whole class is a string compare on exactly the ties it exists to break.
        val rankedNodes = nodes
            .sortedWith(compareByDescending<GraphNode> { scores[it.id] ?: 0.0 }.thenBy { it.id.value })
            .take(maxNodes)

        val evidence = rankedNodes.flatMap { provenance[it.id.value].orEmpty() }
        val rankScores = rankedNodes.associate { it.id.value to (ranks[it.id] ?: 0.0) }

        // nodes.size, not rankedNodes.size: the point of the field is to record what the take()
        // above dropped, so a renderer can say "50 of 74" instead of implying 74 never existed.
        return ContextBundle(rankedNodes, edges, evidence, rankScores, totalNodeCount = nodes.size)
    }

    companion object {
        /**
         * Orders one node's provenance rows by what they *say* rather than by when they were
         * written. Every component is intrinsic to the record, which is the point: the table's
         * autoincrement id would also give a total order, and it would be exactly the insertion
         * order that varies between builds -- a tie-break that reproduces the bug it is meant to
         * close.
         *
         * `extractedAt` is left out for that same reason and is the one field here it would be a
         * mistake to add: it is a wall-clock stamp, so two rows separated only by it would sort by
         * which build wrote them. Every *other* field is compared, including `page` and `textSpan`,
         * which no repository in the benchmark corpus currently needs -- no row in any of the four
         * ties on the rest and differs on those -- but a paginated source such as a PDF is exactly
         * where two rows would otherwise agree on path and lines and separate only by page.
         * Rows identical in all of these are identical in everything a caller reads.
         */
        private val PROVENANCE_ORDER: Comparator<Provenance> = compareBy(
            { it.path },
            { it.lineStart ?: -1 },
            { it.lineEnd ?: -1 },
            { it.page ?: -1 },
            { it.artifactId.value },
            { it.extractor },
            { it.textSpan ?: "" }
        )

        /**
         * Nodes kept per bundle before the PageRank ranking starts discarding. Named rather than
         * repeated as a literal because two layers now have to agree on it: the query layer caps
         * here, and the MCP layer defaults its own `limit` to the same number so an agent that
         * passes nothing gets a cap it can see reported rather than one applied twice.
         */
        const val DEFAULT_MAX_NODES: Int = 50
    }
}
