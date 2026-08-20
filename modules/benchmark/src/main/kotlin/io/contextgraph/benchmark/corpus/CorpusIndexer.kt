package io.contextgraph.benchmark.corpus

import io.contextgraph.benchmark.model.IngestRecord
import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.GraphDb
import io.contextgraph.core.LiteLlmConfig
import io.contextgraph.ingest.ReindexPrimitive
import java.nio.file.Path
import kotlin.time.measureTimedValue

/**
 * Indexes one corpus repo's WITH working copy (AC-2): runs the same [ReindexPrimitive] the
 * CLI's `index` command and the MCP server's `index_project` tool use, so "the WITH arm's
 * graph" is never a bespoke pipeline invocation that could drift from what a real ContextGraph
 * user gets. Writes go through [GraphDb.forLocalWrite], the same seam every non-CI writer
 * uses — `withDir/.contextgraph/graph.local.db` ends up holding the graph, which is exactly
 * what makes `withDir` distinguishable from the untouched WITHOUT copy (AC-7a).
 */
object CorpusIndexer {

    /**
     * [config] must have `litellm.enabled == false` (spec Q1: the WITH arm's index is
     * deliberately built with semantic LLM extraction off, so the measured gain comes from
     * the structural graph alone and ingest cost stays near zero). Enforced here rather than
     * left to convention, since [IngestRecord.tokensUsed]/[IngestRecord.costUsd] are reported
     * as `0` below on the assumption that no LLM calls were made — a caller that flips
     * `litellm.enabled` on would otherwise get a silently wrong (zero) cost recorded.
     */
    fun index(repoId: String, withDir: Path, config: ContextGraphConfig = ContextGraphConfig(litellm = LiteLlmConfig(enabled = false))): IngestRecord {
        require(!config.litellm.enabled) {
            "CorpusIndexer.index requires litellm.enabled=false (spec Q1); got a config with it enabled for repo '$repoId'."
        }

        val dbPath = GraphDb.forLocalWrite(withDir)
        val (_, elapsed) = measureTimedValue {
            ReindexPrimitive.run(withDir, config, dbPath)
        }

        return IngestRecord(
            repoId = repoId,
            durationMillis = elapsed.inWholeMilliseconds,
            // litellm is disabled per the require() above, so no model call was made to spend
            // tokens or incur cost — both are genuinely zero, not "not measured".
            tokensUsed = 0,
            costUsd = 0.0,
            config = config
        )
    }
}
