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

    fun expandNode(nodeId: String, depth: Int = 2, edgeTypes: List<EdgeType> = emptyList()): ContextBundle {
        val (nodes, edges) = expander.expand(listOf(NodeId(nodeId)), depth, edgeTypes)
        return bundler.bundle(nodes)
    }

    fun findPath(fromId: String, toId: String): List<io.contextgraph.core.GraphNode> =
        pathFinder.findPath(NodeId(fromId), NodeId(toId))

    fun getEvidence(nodeId: String) = evidenceRetriever.getEvidence(nodeId)

    fun impactAnalysis(nodeId: String): ContextBundle {
        val id = NodeId(nodeId)
        val incomers = storage.getEdgesTo(id).map { it.source }
        val (nodes, _) = expander.expand(incomers, depth = 2)
        return bundler.bundle(nodes)
    }

    fun relatedFiles(nodeId: String): List<String> {
        val evidence = evidenceRetriever.getEvidence(nodeId)
        return evidence.map { it.path }.distinct()
    }
}
