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

    // --- Bulk verbs (ingest cost) ---
    //
    // Indexing persists rows in large, known-in-advance groups: one artifact's whole
    // extraction result, or a whole pass's rebuilt edge set. Asking for them one row at a
    // time let a backing store charge per-row setup for every one of them -- measured on
    // Keycloak, that was ~1.5 million single-row round trips, and a flight recording of one
    // ingest attributed 91% of all samples to exactly these calls, 19% of it to opening and
    // closing a fresh database connection per row.
    //
    // Each verb below has a default that IS the per-row loop it replaces, in the same order,
    // so an adapter that does not override one behaves identically to before and no
    // implementation is forced to change. An adapter that can do better -- one transaction,
    // one prepared statement -- overrides it. The graph these produce must be row-for-row
    // what the loop produced; that is not a hope, it is what the default body pins down.

    /**
     * Persists one artifact's complete extraction result as a single unit.
     *
     * Order matters and is part of the contract: clear (when asked), then the artifact row,
     * then nodes, edges, unresolved references, and finally each node's provenance.
     */
    fun writeArtifactBatch(batch: ArtifactWriteBatch) {
        if (batch.clearExisting) {
            deleteNodesForArtifact(batch.artifact.id)
            deleteUnresolvedReferencesForArtifact(batch.artifact.id)
        }
        upsertArtifact(batch.artifact)
        batch.nodes.forEach { upsertNode(it) }
        batch.edges.forEach { upsertEdge(it) }
        batch.references.forEach { insertUnresolvedReference(it) }
        batch.nodes.forEach { node ->
            node.provenance.forEach { upsertProvenance(node.id.value, "node", it) }
        }
    }

    /** Persists [nodes] as one unit. Equivalent to calling [upsertNode] on each, in order. */
    fun upsertNodes(nodes: Collection<GraphNode>) {
        nodes.forEach { upsertNode(it) }
    }

    /** Persists [edges] as one unit. Equivalent to calling [upsertEdge] on each, in order. */
    fun upsertEdges(edges: Collection<GraphEdge>) {
        edges.forEach { upsertEdge(it) }
    }
}

/**
 * One artifact's complete extraction result, as the unit [StorageAdapter.writeArtifactBatch]
 * is asked to persist.
 *
 * [clearExisting] is the caller's decision, never the adapter's: one artifact can match
 * several extractors and so produce several results, and the previous run's rows must be
 * cleared on the *first* of them and never again -- otherwise a later result wipes the rows
 * an earlier result for that same artifact just wrote. Only the pipeline's single write
 * consumer knows which result is the first, so only it can say.
 */
data class ArtifactWriteBatch(
    val artifact: Artifact,
    val clearExisting: Boolean,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val references: List<UnresolvedReference>
)

data class GraphStats(
    val artifactCount: Int,
    val nodeCount: Int,
    val edgeCount: Int
)
