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

    // Every label carries the same tokens and the same length, which is what makes bm25 score them
    // identically and the tie under test exist at all. (The ids are equal-length too, but only for
    // readability -- `nodes_fts` declares `id UNINDEXED`, so it is stored and not tokenized, and
    // contributes nothing to bm25 either way.)
    val tiedLabels = listOf("aa", "bb", "cc", "dd", "ee", "ff", "gg", "hh", "ii", "jj", "kk", "ll")
        .map { "${it}Binding.Bind(Request)" }

    /**
     * Indexes [labels] in the order given, searches, and gives the adapter up before returning.
     *
     * Building one index at a time is load-bearing, not hygiene -- though not for the reason
     * `close()` suggests, since [SqliteStorageAdapter.close] is an empty override and closes
     * nothing. Every adapter method runs a bare Exposed `transaction { }`, which resolves against
     * the most recently *connected* database rather than against the instance the method was
     * called on. So the isolation here comes from constructing the next adapter only after this
     * one has finished querying. Two indexes alive at once would make the older answer from the
     * newer one's file, and every test below would compare one database against itself and pass
     * with no tie-break in place at all.
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
        // has to be made on purpose. Ascending by id matches the `ORDER BY n.id` that
        // `segmentCandidates` already ships as a deliberate tie-break for the same reason.
        searchAfterIndexing(tiedLabels.reversed(), limit = 4) shouldBe
            listOf("id-aa", "id-bb", "id-cc", "id-dd")
    }

    test("a node written more than once fills one slot, not the whole page") {
        // `nodes_fts` is FTS5 with `id UNINDEXED`, so `INSERT OR REPLACE` has no unique index to
        // target and every write appends a row. Duplicates are ordinary, not a corrupted-database
        // edge case: a freshly built gin index holds 3821 rows for 3750 ids.
        //
        // This is the failure mode that ordering by id introduces and that nothing else here
        // catches. Ordering by rank alone scattered a node's copies through docid order, so a page
        // held distinct ids by luck; grouping tied rows by id stacks every copy of one node
        // together, and without a GROUP BY the whole page becomes that one node repeated.
        val dir = Files.createTempDirectory("search-cut-duplicate-fts-rows")
        val storage = SqliteStorageAdapter(dir.resolve("graph.db"))
        try {
            // The duplicated node has to sort *first*, or the cut removes it before its copies can
            // do any damage and the test passes on the broken code for the wrong reason. `id-aa`
            // is lowest among these ids, so every one of its sixteen copies -- the worst real
            // count measured in gin, on `render/render.go#_` -- lands ahead of all eleven peers.
            repeat(16) {
                storage.upsertNode(GraphNode(NodeId("id-aa"), NodeType.Method, tiedLabels.first()))
            }
            tiedLabels.drop(1).forEach { label ->
                storage.upsertNode(GraphNode(NodeId("id-${label.take(2)}"), NodeType.Method, label))
            }

            val page = storage.searchNodes("Bind Request", limit = 10).map { it.id.value }

            page.count { it == "id-aa" } shouldBe 1
            page.distinct() shouldBe page
            page.size shouldBe 10
        } finally {
            storage.close()
        }
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
