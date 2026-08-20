package io.contextgraph.benchmark.corpus

import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.GraphDb
import io.contextgraph.core.LiteLlmConfig
import io.contextgraph.storage.SqliteStorageAdapter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * AC-2: indexing cost is measured and recorded separately, litellm stays off for the WITH
 * arm's index (spec Q1). Indexes a local git fixture (`LocalGitFixture`) through the real
 * `ReindexPrimitive`/`IngestPipeline` -- no network, and proves the graph is genuinely
 * queryable afterward rather than just asserting `IngestRecord`'s shape.
 */
class CorpusIndexerTest : FunSpec({

    test("indexing a working copy produces a queryable graph and a correctly-shaped IngestRecord") {
        val root = Files.createTempDirectory("corpus-indexer-")
        try {
            val repo = LocalGitFixture.create(root.resolve("with"))

            val record = CorpusIndexer.index("fixture-repo", repo.path)

            record.repoId shouldBe "fixture-repo"
            record.durationMillis shouldBeGreaterThanOrEqual 0L
            record.tokensUsed shouldBe 0L
            record.costUsd shouldBe 0.0
            record.config.litellm.enabled shouldBe false

            val dbPath = GraphDb.forRead(repo.path)
            val storage = SqliteStorageAdapter(dbPath)
            try {
                val stats = storage.getStats()
                stats.artifactCount shouldBeGreaterThan 0
                stats.nodeCount shouldBeGreaterThan 0
            } finally {
                storage.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("rejects a config with litellm enabled, since tokensUsed/costUsd would be reported wrong") {
        val root = Files.createTempDirectory("corpus-indexer-litellm-guard-")
        try {
            val repo = LocalGitFixture.create(root)
            shouldThrow<IllegalArgumentException> {
                CorpusIndexer.index("fixture-repo", repo.path, ContextGraphConfig(litellm = LiteLlmConfig(enabled = true)))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
