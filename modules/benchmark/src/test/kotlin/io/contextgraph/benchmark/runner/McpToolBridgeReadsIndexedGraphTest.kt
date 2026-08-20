package io.contextgraph.benchmark.runner

import io.contextgraph.benchmark.corpus.CorpusIndexer
import io.contextgraph.benchmark.corpus.LocalGitFixture
import io.contextgraph.mcp.ExploreResponse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.nio.file.Files

/**
 * Regression test for the defect the Lead found while reviewing task 12's smoke run:
 * [ContextGraphMcpToolBridge] used to open a hand-built `.contextgraph/graph.db` path directly,
 * bypassing [io.contextgraph.core.GraphDb]'s read seam. [CorpusIndexer] (like every real writer)
 * writes through [io.contextgraph.core.GraphDb.forLocalWrite], which lands in the
 * `graph.local.db` overlay -- so the bridge was opening a file that had never been written,
 * and [io.contextgraph.storage.SqliteStorageAdapter] silently creates and schema-migrates
 * whatever path it's handed. The WITH_TOOLS arm was therefore always querying an empty graph:
 * a green smoke run proved nothing about the tool arm, and a full run would have measured
 * ContextGraph's structural gain as approximately zero -- the exact number the whole benchmark
 * suite exists to produce.
 *
 * This test indexes a real fixture through the real [CorpusIndexer] (the same call
 * [io.contextgraph.benchmark.corpus.CorpusPreparationStep] makes for the WITH copy), then asks
 * [ContextGraphMcpToolBridge] -- not a lower-level storage check that would miss the bug -- a
 * question a real indexed answer must match, and asserts the response is non-empty. Verified by
 * hand against both the pre-fix and post-fix bridge (see the task 12 report to the Lead): red
 * against the old hardcoded `graph.db` path (empty=true, zero symbols, because that file had
 * never been written), green against the fixed [io.contextgraph.core.GraphDb.forRead] seam.
 */
class McpToolBridgeReadsIndexedGraphTest : FunSpec({

    val json = Json { ignoreUnknownKeys = true }

    test("a bridge pointed at an indexed working copy observes a non-empty graph, not an accidentally-created empty one") {
        val root = Files.createTempDirectory("mcp-bridge-reads-real-graph-")
        try {
            val repo = LocalGitFixture.create(root.resolve("with"))

            // Real indexing, through the exact call CorpusPreparationStep makes for the WITH
            // copy -- writes land in the graph.local.db overlay, never the baseline.
            CorpusIndexer.index("fixture-repo", repo.path)

            val bridge = ContextGraphMcpToolBridge(repo.path)
            try {
                // LocalGitFixture's src/Foo.kt declares `fun foo(): Int = 42` -- "foo" is a real,
                // indexed symbol name a correctly-wired bridge must be able to find.
                val responseJson = bridge.invoke(
                    ContextGraphMcpToolBridge.TOOL_NAME,
                    """{"question":"foo"}"""
                )
                val response = json.decodeFromString(ExploreResponse.serializer(), responseJson)

                // The bug this guards against: empty=true and zero symbols, not because the
                // fixture wasn't indexed (it was -- see CorpusIndexerTest), but because the
                // bridge was reading a different, never-written file.
                response.empty shouldBe false
                response.symbols.shouldNotBeEmpty()
            } finally {
                bridge.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("a bridge pointed at a working copy with no index at all fails loudly instead of silently creating an empty graph") {
        val root = Files.createTempDirectory("mcp-bridge-no-index-")
        try {
            // Deliberately never indexed -- no .contextgraph/ directory at all.
            val exception = runCatching { ContextGraphMcpToolBridge(root) }.exceptionOrNull()
            (exception != null) shouldBe true
            (exception is IllegalStateException) shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
