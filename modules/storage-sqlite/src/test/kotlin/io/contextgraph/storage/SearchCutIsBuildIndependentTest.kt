package io.contextgraph.storage

import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.NodeType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

/**
 * [SqliteStorageAdapter.searchNodes] ranks by bm25 and then cuts at `limit`, so when candidates tie
 * on bm25 the cut decides *which* of them a caller ever sees -- not merely what order they arrive
 * in.
 *
 * bm25 ties are the normal case for a codebase rather than an exotic one: same-shaped declarations
 * produce same-shaped labels. gin's `binding` package declares thirteen `Bind(*http.Request,any)`
 * methods that tie exactly, against a seed query whose limit is 10.
 *
 * Without a tie-break the surviving ten were FTS5 docid order, which is insertion order, which
 * differs between index builds because ingest extracts concurrently. Two builds of gin proven
 * identical in content down to nine measures returned different tens -- one containing the
 * gold-cited `binding/json.go` and the other not -- moving that question's published reciprocal
 * rank between 0.5 and 1.0 and the repo's MRR between 0.656 and 0.719.
 *
 * So each test here indexes the same nodes twice, in two different insertion orders, and compares.
 * Querying one index twice would pass without a tie-break and prove nothing: a cut that is stable
 * against a fixed table is not the same claim as a cut that is a function of the content.
 */
class SearchCutIsBuildIndependentTest : FunSpec({

    // Every label carries the same tokens and the same length, and so does every id -- the FTS
    // table indexes id, label and properties together, so a length difference anywhere would give
    // bm25 something real to separate these on and the tie under test would quietly stop existing.
    val tiedLabels = listOf("aa", "bb", "cc", "dd", "ee", "ff", "gg", "hh", "ii", "jj", "kk", "ll")
        .map { "${it}Binding.Bind(Request)" }

    /**
     * Indexes [labels] in the order given, runs [query], and closes before returning.
     *
     * Closing before the next index is built is load-bearing, not hygiene. Every adapter method
     * runs a bare Exposed `transaction { }`, which resolves against the most recently connected
     * database rather than against the instance it was called on -- so two live adapters make the
     * older one silently answer from the newer one's file. Two indexes open at once would compare
     * one database against itself and pass without a tie-break in place.
     */
    fun searchAfterIndexing(labels: List<String>, limit: Int): List<String> {
        val dir = Files.createTempDirectory("search-cut-build-independence")
        val storage = SqliteStorageAdapter(dir.resolve("graph.db"))
        return try {
            labels.forEach { label ->
                // The id is derived from the label, so two indexes built from the same labels hold
                // identical content and differ in insertion order alone -- exactly what two builds
                // of one repository differ by.
                storage.upsertNode(GraphNode(NodeId("id-${label.take(2)}"), NodeType.Method, label))
            }
            storage.searchNodes("Bind Request", limit = limit).map { it.id.value }
        } finally {
            storage.close()
        }
    }

    test("a bm25 tie cut by limit keeps the same nodes whatever order they were indexed in") {
        val forwards = searchAfterIndexing(tiedLabels, limit = 5)
        val backwards = searchAfterIndexing(tiedLabels.reversed(), limit = 5)

        // Load-bearing: if the query stopped returning a short page there would be no cut left to
        // be arbitrary, and the assertion below would hold for the wrong reason.
        forwards.size shouldBe 5
        forwards shouldBe backwards
    }

    test("the surviving set is decided by node id, so it is predictable rather than merely stable") {
        // Naming the expected ids states which rows the tie-break selects, so a change to the rule
        // has to be made on purpose. Ascending by id matches the `ORDER BY n.id` that getAllNodes
        // already ships as a deliberate tie-break for the same reason.
        searchAfterIndexing(tiedLabels.reversed(), limit = 4) shouldBe
            listOf("id-aa", "id-bb", "id-cc", "id-dd")
    }

    test("the fixture really does tie -- otherwise the tests above would pass on bm25 alone") {
        // A guard on the fixture, not on the adapter. Were these labels ever to stop tying, the
        // two tests above would still pass and would silently assert nothing about tie-breaking.
        // A tie is exactly what lets insertion order change the answer, so the check is that
        // reversing it would have: the full page comes back in id order, not in the order written.
        val writtenOrder = tiedLabels.reversed().map { "id-${it.take(2)}" }
        val returned = searchAfterIndexing(tiedLabels.reversed(), limit = tiedLabels.size)

        returned.size shouldBe tiedLabels.size
        returned shouldNotBe writtenOrder
        returned shouldBe writtenOrder.sorted()
    }
})
