package io.contextgraph.query

import io.contextgraph.core.ArtifactId
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.contextgraph.core.Provenance
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlinx.datetime.Instant

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

    // ---------------------------------------------------------------------------------------
    // Ties.
    //
    // The test directly above passes on a bundler with no tie-break at all: it hands `bundle` the
    // *same list object* twice, so a stable sort preserves the same input order both times and
    // agrees with itself about an order it never decided. A stable sort preserves an accident when
    // the input has no order of its own -- and `nodes` has none, being SQLite physical row order,
    // which differs between index builds because ingest extracts concurrently.
    //
    // So each test below feeds the same candidates in two different input orders. That is the
    // difference between proving the ranking is a function of the candidates and proving only that
    // it is repeatable when nothing varies.
    // ---------------------------------------------------------------------------------------

    // Confidence 0.0 makes the tie exact by construction rather than by arithmetic luck: the graph
    // term is `pageRank * confidence`, so every candidate scores exactly 0.0 whatever PageRank
    // returns, and no epsilon between two vertices can quietly do the tie-break's job for it.
    fun tied(id: String) = unlinked(id, 0.0)

    test("candidates tied on score rank by node id, not by the order they arrived in") {
        val nodes = listOf(tied("charlie"), tied("alpha"), tied("bravo"))

        val forwards = bundler.bundle(nodes).nodes.map { it.id.value }
        val backwards = bundler.bundle(nodes.reversed()).nodes.map { it.id.value }

        forwards shouldBe listOf("alpha", "bravo", "charlie")
        backwards shouldBe forwards
    }

    test("which tied candidates survive the cut does not depend on the order they arrived in") {
        // The sharp edge is not the order of what survives, it is *what survives*. `take` cuts the
        // sorted list, so with more tied candidates than slots an input-order-dependent sort makes
        // the surviving set itself an artifact of how SQLite happened to lay the rows out.
        val nodes = listOf(tied("e"), tied("b"), tied("f"), tied("a"), tied("d"), tied("c"))

        val forwards = bundler.bundle(nodes, maxNodes = 3).nodes.map { it.id.value }
        val backwards = bundler.bundle(nodes.reversed(), maxNodes = 3).nodes.map { it.id.value }

        forwards shouldBe listOf("a", "b", "c")
        backwards shouldBe forwards
    }

    test("a tie is broken the same way whether or not a query did the scoring") {
        // The `relevance != null` path is the one `search` and `buildContext` take, and it is the
        // one the published retrieval measurement runs through. An all-inert query leaves every
        // candidate on the same score, exactly as the expanded-neighbour population does in a real
        // `buildContext` call.
        val nodes = listOf(tied("zulu"), tied("mike"), tied("alfa"))
        val relevance = QueryRelevance.of("entirely unrelated wording", seedsInRankOrder = emptyList())

        val forwards = bundler.bundle(nodes, relevance = relevance).nodes.map { it.id.value }
        val backwards = bundler.bundle(nodes.reversed(), relevance = relevance).nodes.map { it.id.value }

        forwards shouldBe listOf("alfa", "mike", "zulu")
        backwards shouldBe forwards
    }

    test("evidence for one node is ordered by what its rows say, not by the order they were written") {
        // `getProvenanceFor` issues no ORDER BY, so a node's rows come back in row order -- and the
        // benchmark's ContextGraph side reads its whole ranked file list off `evidence`. Writing
        // the rows in descending path order is what a build that happened to extract them that way
        // would leave behind.
        val node = unlinked("multi-file", 0.9)
        listOf("src/zebra.kt", "src/mango.kt", "src/apple.kt").forEach { path ->
            storage.upsertProvenance(
                node.id.value,
                "node",
                Provenance(ArtifactId("artifact-$path"), path, extractor = "test", extractedAt = Instant.fromEpochSeconds(0))
            )
        }

        val evidence = bundler.bundle(listOf(node)).evidence.map { it.path }

        evidence shouldBe listOf("src/apple.kt", "src/mango.kt", "src/zebra.kt")
    }
})
