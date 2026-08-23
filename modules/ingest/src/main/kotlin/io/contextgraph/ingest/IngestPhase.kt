package io.contextgraph.ingest

/**
 * The named parts an index run's wall-clock is split into.
 *
 * This exists because a 107x ingest-cost regression against a comparable tool survived
 * unnoticed for as long as it did: nothing on the ingest path ever said where its time went,
 * so the only instrument that could see it was a benchmark run costing seventy-one minutes.
 * A breakdown printed by every ordinary `index` makes the next one visible immediately.
 *
 * Two kinds of entry, and the difference matters more than it looks:
 *
 *  - **[partitionsTotal] = true** — a phase [IngestPipeline.index] runs strictly one after
 *    another. These sum to the total, minus whatever is left over, and that leftover is
 *    reported rather than dropped: an unattributed 40% is the single most useful thing this
 *    instrument can say.
 *  - **[partitionsTotal] = false** — a cost *inside* pass 1, summed across the threads that
 *    paid it. Extraction runs concurrently on [kotlinx.coroutines.Dispatchers.IO] while the
 *    single write consumer drains its channel, so these overlap each other and overlap pass
 *    1's own wall-clock. Presenting them as if they partitioned anything would be worse than
 *    not measuring them at all, so they are labelled and indented as what they are.
 */
enum class IngestPhase(val label: String, val partitionsTotal: Boolean) {
    MODULE_DETECTION("module detection", true),
    PASS_1("pass 1 (discover, extract, write)", true),
    PASS_2_RESOLVE("pass 2 reference resolution", true),
    PASS_3_GROUP("pass 3 sibling grouping", true),

    FILE_TRIAGE("file triage (checksum, stat, lookup)", false),
    EXTRACTION("extraction", false),
    DB_WRITE("database writes", false);

    companion object {
        /** The phases that run one after another, in the order [IngestPipeline.index] runs them. */
        val sequential: List<IngestPhase> = entries.filter { it.partitionsTotal }

        /** The costs measured inside [PASS_1], which overlap each other and it. */
        val withinPass1: List<IngestPhase> = entries.filterNot { it.partitionsTotal }
    }
}
