package io.contextgraph.query

import io.contextgraph.core.EdgeType
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.StorageAdapter
import io.contextgraph.graph.GraphAlgorithms

class QueryEngine(private val storage: StorageAdapter) {
    private val classifier = IntentClassifier()
    private val searcher = NodeSearcher(storage)
    private val expander = GraphExpander(storage)
    private val pathFinder = PathFinder(storage)
    private val evidenceRetriever = EvidenceRetriever(storage)
    private val bundler = ContextBundler(storage, GraphAlgorithms())

    fun search(
        query: String,
        types: List<NodeType> = emptyList(),
        minConfidence: Double = 0.0,
        limit: Int = 20
    ): QueryResult {
        val nodes = searcher.search(query, types, minConfidence, limit)
        val bundle = bundler.bundle(nodes)
        val avgConf = if (nodes.isEmpty()) 0.0 else nodes.sumOf { it.confidence } / nodes.size
        return QueryResult(bundle.nodes, bundle.edges, bundle.evidence, "Found ${nodes.size} nodes", avgConf)
    }

    fun buildContext(task: String, depth: Int = 2): ContextBundle {
        val intent = classifier.classify(task)
        val typeFilter = when (intent) {
            QueryIntent.DEVELOPER -> listOf(NodeType.Function, NodeType.Class, NodeType.Method, NodeType.Module)
            QueryIntent.RESEARCHER -> listOf(NodeType.Concept, NodeType.Claim, NodeType.Methodology)
            QueryIntent.GENERAL -> emptyList()
        }
        val seeds = searcher.search(task, typeFilter, minConfidence = 0.5, limit = 10)
        val (nodes, edges) = expander.expand(seeds.map { it.id }, depth)
        return bundler.bundle(nodes)
    }

    fun expandNode(
        nodeId: String,
        depth: Int = 2,
        edgeTypes: List<EdgeType> = emptyList(),
        maxNodes: Int = ContextBundler.DEFAULT_MAX_NODES
    ): ContextBundle {
        val (nodes, edges) = expander.expand(listOf(NodeId(nodeId)), depth, edgeTypes)
        return bundler.bundle(nodes, maxNodes)
    }

    fun findPath(fromId: String, toId: String): List<io.contextgraph.core.GraphNode> =
        pathFinder.findPath(NodeId(fromId), NodeId(toId))

    fun getEvidence(nodeId: String) = evidenceRetriever.getEvidence(nodeId)

    /**
     * Everything that points *at* [nodeId] -- its direct dependents, and for a method its call
     * sites.
     *
     * Direct only, deliberately. This used to expand two hops out from the incomers and then keep
     * the 50 highest-PageRank nodes of the result, which broke the tool for the question it names.
     * Asked which 74 methods call one builder method, it answered with a blast radius of hundreds
     * ranked by graph centrality, of which the 50 kept were mostly *not* callers -- a direct caller
     * in a leaf file loses a centrality contest to a hub two hops away every time. So the tool
     * both padded the answer and dropped members of it, and no caller could tell which was which.
     *
     * A dependent of a dependent is reachable by calling this again on that node; a direct caller
     * that never appears is not reachable at all. [limit] caps the answer but never silently: the
     * returned bundle carries the full incomer count for the caller to compare against.
     */
    fun impactAnalysis(nodeId: String, limit: Int = ContextBundler.DEFAULT_MAX_NODES): ContextBundle {
        val id = NodeId(nodeId)
        val incomingEdges = storage.getEdgesTo(id)
        val dependents = incomingEdges.map { it.source }.distinct().mapNotNull { storage.getNode(it) }
        return ContextBundle(
            nodes = dependents.take(limit),
            edges = incomingEdges,
            evidence = dependents.take(limit).flatMap { storage.getProvenance(it.id.value) },
            rankScores = emptyMap(),
            totalNodeCount = dependents.size
        )
    }

    fun relatedFiles(nodeId: String): List<String> {
        val evidence = evidenceRetriever.getEvidence(nodeId)
        return evidence.map { it.path }.distinct()
    }
}
