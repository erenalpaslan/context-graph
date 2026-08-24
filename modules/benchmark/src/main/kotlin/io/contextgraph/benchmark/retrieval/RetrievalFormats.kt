package io.contextgraph.benchmark.retrieval

import java.util.Locale

/**
 * How an ingest duration and an index size on disk are rendered, wherever a [ToolIngestCost]
 * reaches a reader.
 *
 * [RetrievalReportGenerator] and [PublishedSummaries] both render the same run's ingest figures --
 * one into the full report, the other into `README.md` -- and until now each carried its own
 * byte-identical copy of this formatting. Two copies is how a change to one stops rendering the
 * same duration the same way as the other, so the report and the README would disagree about a
 * number that is supposed to be the same measurement. This is the one place either surface derives
 * that text from, the same reason [RetrievalAggregate.sideAggregate] lives beside the type it maps
 * rather than inside either of its two callers.
 */
internal object RetrievalFormats {

    /**
     * `n/a` for an absent duration; a zero is the ingest pipeline's own sentinel for "this run
     * found the index already built and did not rebuild it", never a real zero-length build.
     */
    fun fmtDuration(millis: Long?): String = when {
        millis == null -> "n/a"
        millis == 0L -> "reused existing index"
        millis < 1_000 -> "${millis}ms"
        millis < 60_000 -> String.format(Locale.ROOT, "%.1fs", millis / 1000.0)
        else -> String.format(Locale.ROOT, "%dm %ds", millis / 60_000, (millis % 60_000) / 1000)
    }

    fun fmtBytes(bytes: Long?): String = when {
        bytes == null -> "_size unknown_"
        bytes >= 1_000_000_000 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.ROOT, "%.1f kB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
