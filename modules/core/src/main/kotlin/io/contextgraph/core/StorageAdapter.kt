package io.contextgraph.core

interface StorageAdapter {
    fun upsertArtifact(artifact: Artifact)
    fun getArtifact(id: ArtifactId): Artifact?
    fun deleteNodesForArtifact(artifactId: ArtifactId)
    fun upsertNode(node: GraphNode)
    fun upsertEdge(edge: GraphEdge)
    fun upsertProvenance(entityId: String, entityKind: String, provenance: Provenance)
    fun searchNodes(
        query: String,
        types: List<NodeType> = emptyList(),
        minConfidence: Double = 0.0,
        limit: Int = 20
    ): List<GraphNode>
    fun getNode(id: NodeId): GraphNode?
    fun getEdgesFrom(source: NodeId): List<GraphEdge>
    fun getEdgesTo(target: NodeId): List<GraphEdge>
    fun getProvenance(entityId: String): List<Provenance>

    /**
     * [getProvenance] for many entities at once, keyed by entity id. An entity with no provenance
     * is absent from the map rather than present with an empty list.
     *
     * Exists because ranking needs every *candidate's* file, not just every survivor's: a bundle
     * that scores a thousand candidates on where their file sits would otherwise issue a thousand
     * single-row queries to learn it. The default implementation is exactly that loop, so an
     * adapter with no cheaper way to answer stays correct without implementing anything; an
     * adapter over a real database overrides it with one query per chunk.
     */
    fun getProvenanceFor(entityIds: Collection<String>): Map<String, List<Provenance>> =
        entityIds.distinct().associateWith { getProvenance(it) }.filterValues { it.isNotEmpty() }
    fun getAllNodes(minConfidence: Double = 0.0): List<GraphNode>
    fun getAllEdges(minConfidence: Double = 0.0): List<GraphEdge>
    fun getAllArtifacts(): List<Artifact>
    fun getStats(): GraphStats
    fun close()

    // --- Symbol table / two-pass resolution (slice 09) ---

    /** Declarations, indexed by exact [GraphNode.label] -- the symbol table pass 2 probes. */
    fun findNodesByLabel(label: String): List<GraphNode>

    /** Persists one pass-1 unresolved reference, replacing nothing -- see [deleteUnresolvedReferencesForArtifact]. */
    fun insertUnresolvedReference(reference: UnresolvedReference)

    /** Drops every unresolved reference a prior pass 1 run recorded for [artifactId], before it re-emits its current set. */
    fun deleteUnresolvedReferencesForArtifact(artifactId: ArtifactId)

    /** Every unresolved reference persisted by any file -- including ones pass 1 skipped this run because they were unchanged. */
    fun getAllUnresolvedReferences(): List<UnresolvedReference>

    /** Removes every edge of [type]. Pass 2 uses this to wipe the whole resolved `Calls` set before recomputing it. */
    fun deleteEdgesOfType(type: EdgeType)
}

data class GraphStats(
    val artifactCount: Int,
    val nodeCount: Int,
    val edgeCount: Int
)
