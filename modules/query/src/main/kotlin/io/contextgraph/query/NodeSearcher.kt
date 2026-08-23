package io.contextgraph.query

import io.contextgraph.core.GraphNode
import io.contextgraph.core.IdentifierSplitter
import io.contextgraph.core.NodeType
import io.contextgraph.core.StorageAdapter

class NodeSearcher(private val storage: StorageAdapter) {
    fun search(
        query: String,
        types: List<NodeType> = emptyList(),
        minConfidence: Double = 0.0,
        limit: Int = 20
    ): List<GraphNode> = storage.searchNodes(query, types, minConfidence, limit)

    /**
     * Nodes whose label is exactly one of [query]'s words -- the supplement that keeps an exact
     * name from being absent rather than merely ranked low.
     *
     * Relevance search buries a short name under the long compound names containing it, and the
     * candidate limit then cuts it before any re-ranking can reach it. This asks the question
     * relevance cannot: *is there something called this?*
     *
     * Bounded on both sides. Words shorter than [MIN_WORD_LENGTH] are skipped -- an identifier
     * two letters long is not what the asker meant by a word of their question -- and at most
     * [maxPerWord] nodes are admitted for any one word, so a query word that names a hundred
     * things contributes a sample rather than a hundred candidates.
     */
    fun exactNameMatches(query: String, maxPerWord: Int = DEFAULT_MAX_PER_WORD): List<GraphNode> {
        val words = IdentifierSplitter.split(query)
            .map { it.lowercase() }
            .filter { it.length >= MIN_WORD_LENGTH }
            .distinct()
        if (words.isEmpty()) return emptyList()

        return storage.findNodesByLabelsIgnoreCase(words)
            .groupBy { it.label.lowercase() }
            .values
            .flatMap { it.take(maxPerWord) }
    }

    fun getById(id: String): GraphNode? = storage.getNode(io.contextgraph.core.NodeId(id))

    companion object {
        private const val MIN_WORD_LENGTH = 3
        const val DEFAULT_MAX_PER_WORD = 5
    }
}
