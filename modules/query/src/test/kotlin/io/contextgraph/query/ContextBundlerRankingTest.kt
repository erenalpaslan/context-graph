package io.contextgraph.query

import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * The ordering contract of [ContextBundler.bundle], pinned from both sides: what a query is
 * allowed to change, and what it must leave exactly as it was.
 *
 * Fixtures are invented here and share nothing with the benchmark's question sets -- these tests
 * prove the ranking's own logic, not its fit to one gold set's wording.
 */
class ContextBundlerRankingTest : FunSpec({

    lateinit var storage: SqliteStorageAdapter
    lateinit var bundler: ContextBundler

    beforeEach {
        val tmpDir = Files.createTempDirectory("context-bundler-ranking-test")
        storage = SqliteStorageAdapter(tmpDir.resolve("graph.db"))
        bundler = ContextBundler(storage)
    }

    afterEach { storage.close() }

    // No edges between them, so PageRank is uniform and `pageRank * confidence` reduces to
    // confidence -- which makes the graph term's contribution to the order legible on its own.
    fun unlinked(id: String, confidence: Double) =
        GraphNode(NodeId(id), NodeType.Class, "Node$id", confidence = confidence)
            .also { storage.upsertNode(it) }

    test("with no query, the order is the graph's alone -- pageRank times confidence") {
        val nodes = listOf(unlinked("low", 0.2), unlinked("high", 0.9), unlinked("mid", 0.5))

        bundler.bundle(nodes).nodes.map { it.id.value } shouldBe listOf("high", "mid", "low")
    }

    test("a query that matches nothing about the candidates leaves the graph's order intact") {
        val nodes = listOf(unlinked("low", 0.2), unlinked("high", 0.9), unlinked("mid", 0.5))

        // Nothing to catch on: no candidate was a search hit, none carries a file, and the query's
        // words appear in no label. Every query-derived term is inert, so the graph decides.
        val ranked = bundler.bundle(
            nodes,
            relevance = QueryRelevance.of("entirely unrelated wording", seedsInRankOrder = emptyList())
        )

        ranked.nodes.map { it.id.value } shouldBe listOf("high", "mid", "low")
    }

    test("the search layer's own ordering survives into the final order") {
        // Deliberately inverted against the graph: the best search hit is the least central
        // candidate. Before the query reached this sort, "best" here lost to "worst" every time.
        val best = unlinked("best-search-hit", 0.2)
        val worst = unlinked("worst-search-hit", 0.9)
        val nodes = listOf(worst, best)

        val ranked = bundler.bundle(
            nodes,
            relevance = QueryRelevance.of("anything", seedsInRankOrder = listOf(best.id, worst.id))
        )

        ranked.nodes.map { it.id.value } shouldBe listOf("best-search-hit", "worst-search-hit")
    }

    test("a candidate that was never a search hit ranks below one that was") {
        val hit = unlinked("search-hit", 0.1)
        val neighbour = unlinked("expanded-neighbour", 1.0)

        val ranked = bundler.bundle(
            listOf(neighbour, hit),
            relevance = QueryRelevance.of("anything", seedsInRankOrder = listOf(hit.id))
        )

        ranked.nodes.map { it.id.value } shouldBe listOf("search-hit", "expanded-neighbour")
    }

    test("ranking the same candidates twice gives the same order") {
        val nodes = listOf(unlinked("a", 0.5), unlinked("b", 0.5), unlinked("c", 0.5))
        val relevance = QueryRelevance.of("node", nodes.map { it.id })

        val first = bundler.bundle(nodes, relevance = relevance).nodes.map { it.id.value }
        val second = bundler.bundle(nodes, relevance = relevance).nodes.map { it.id.value }

        first shouldBe second
    }
})
