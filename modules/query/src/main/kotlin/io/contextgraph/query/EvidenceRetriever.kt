package io.contextgraph.query

import io.contextgraph.core.Provenance
import io.contextgraph.core.StorageAdapter

class EvidenceRetriever(private val storage: StorageAdapter) {
    fun getEvidence(entityId: String): List<Provenance> = storage.getProvenance(entityId)

    fun getEvidenceForAll(entityIds: List<String>): Map<String, List<Provenance>> =
        entityIds.associateWith { storage.getProvenance(it) }
}
