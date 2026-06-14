package io.contextgraph.ingest

import java.util.concurrent.atomic.AtomicInteger

class IndexStats {
    private val _artifactCount = AtomicInteger(0)
    private val _nodeCount = AtomicInteger(0)
    private val _edgeCount = AtomicInteger(0)
    private val _skipped = AtomicInteger(0)
    private val _failed = AtomicInteger(0)

    val artifactCount: Int get() = _artifactCount.get()
    val nodeCount: Int get() = _nodeCount.get()
    val edgeCount: Int get() = _edgeCount.get()
    val skipped: Int get() = _skipped.get()
    val failed: Int get() = _failed.get()

    fun incrementArtifacts() = _artifactCount.incrementAndGet()
    fun addNodes(n: Int) = _nodeCount.addAndGet(n)
    fun addEdges(n: Int) = _edgeCount.addAndGet(n)
    fun incrementSkipped() = _skipped.incrementAndGet()
    fun incrementFailed() = _failed.incrementAndGet()

    override fun toString(): String =
        "IndexStats(artifacts=$artifactCount, nodes=$nodeCount, edges=$edgeCount, skipped=$skipped, failed=$failed)"
}
