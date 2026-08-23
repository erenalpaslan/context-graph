package io.contextgraph.ingest

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

class IndexStats {
    private val _artifactCount = AtomicInteger(0)
    private val _nodeCount = AtomicInteger(0)
    private val _edgeCount = AtomicInteger(0)
    private val _skipped = AtomicInteger(0)
    private val _failed = AtomicInteger(0)
    private val _parseWarnings = AtomicInteger(0)

    val artifactCount: Int get() = _artifactCount.get()
    val nodeCount: Int get() = _nodeCount.get()
    val edgeCount: Int get() = _edgeCount.get()
    val skipped: Int get() = _skipped.get()
    val failed: Int get() = _failed.get()

    /**
     * Count of [io.contextgraph.core.ExtractionDiagnostic]s a covered language's grammar
     * raised while parsing (e.g. a `.java` file with a syntax error) -- distinct from
     * [failed], which counts an extractor *throwing*. A file with no grammar at all (an
     * uncovered extension, e.g. `.go`) produces neither: see
     * `io.contextgraph.extractors.TreeSitterExtractor`.
     */
    val parseWarnings: Int get() = _parseWarnings.get()

    fun incrementArtifacts() = _artifactCount.incrementAndGet()
    fun addNodes(n: Int) = _nodeCount.addAndGet(n)
    fun addEdges(n: Int) = _edgeCount.addAndGet(n)
    fun incrementSkipped() = _skipped.incrementAndGet()
    fun incrementFailed() = _failed.incrementAndGet()
    fun addParseWarnings(n: Int) = _parseWarnings.addAndGet(n)

    // --- Where the time went (see IngestPhase for why this exists) ---

    private val _phaseNanos = AtomicLongArray(IngestPhase.entries.size)
    private val _totalNanos = AtomicLong(0)

    /**
     * Adds [nanos] to [phase]. Called from several threads for the phases measured inside
     * pass 1 -- extraction runs concurrently -- hence the atomics; the cost is one add per
     * *file*, never one per node, edge or row.
     */
    fun addPhaseNanos(phase: IngestPhase, nanos: Long) {
        _phaseNanos.addAndGet(phase.ordinal, nanos)
    }

    fun setTotalNanos(nanos: Long) {
        _totalNanos.set(nanos)
    }

    fun nanosOf(phase: IngestPhase): Long = _phaseNanos.get(phase.ordinal)

    /** Wall-clock of the whole index run, the number every sequential phase is a share of. */
    val totalNanos: Long get() = _totalNanos.get()

    /**
     * Whatever the sequential phases do not account for.
     *
     * Reported rather than absorbed into the nearest phase: the phases below are the parts of
     * indexing anyone thought to measure, and a large remainder is the instrument telling you
     * the interesting cost is somewhere nobody has named yet. Never silently clamped to zero
     * -- a negative value would mean two phases double-counted, which is a defect worth
     * seeing rather than hiding.
     */
    val unattributedNanos: Long
        get() = totalNanos - IngestPhase.sequential.sumOf { nanosOf(it) }

    /**
     * The breakdown, as lines ready to print. Every trigger that reports an index run shows
     * this, unconditionally -- a breakdown behind a flag or a debug level is one nobody
     * checks, which is the state that let a 107x regression live.
     */
    fun timingReport(): List<String> {
        if (totalNanos <= 0L) return emptyList()
        val lines = mutableListOf("Time: ${formatNanos(totalNanos)} total")
        IngestPhase.sequential.forEach { phase ->
            lines += "  ${phase.label.padEnd(34)} ${formatNanos(nanosOf(phase)).padStart(9)}  ${percentOf(nanosOf(phase))}"
            if (phase == IngestPhase.PASS_1) {
                IngestPhase.withinPass1.forEach { inner ->
                    lines += "    - ${inner.label.padEnd(36)} ${formatNanos(nanosOf(inner)).padStart(9)}"
                }
                lines += "    (the three above are summed across threads: extraction is concurrent, so they overlap each other and pass 1)"
            }
        }
        lines += "  ${"unattributed".padEnd(34)} ${formatNanos(unattributedNanos).padStart(9)}  ${percentOf(unattributedNanos)}"
        return lines
    }

    private fun percentOf(nanos: Long): String =
        if (totalNanos <= 0L) "" else String.format("%5.1f%%", nanos * 100.0 / totalNanos)

    private fun formatNanos(nanos: Long): String {
        val seconds = nanos / 1_000_000_000.0
        return when {
            seconds >= 60 -> "${(seconds / 60).toInt()}m${String.format("%04.1f", seconds % 60)}s"
            seconds >= 1 -> String.format("%.1fs", seconds)
            else -> String.format("%.0fms", seconds * 1000)
        }
    }

    override fun toString(): String =
        "IndexStats(artifacts=$artifactCount, nodes=$nodeCount, edges=$edgeCount, skipped=$skipped, failed=$failed, parseWarnings=$parseWarnings)"
}
