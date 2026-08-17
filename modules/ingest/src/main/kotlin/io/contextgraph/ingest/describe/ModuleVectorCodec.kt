package io.contextgraph.ingest.describe

import java.nio.ByteBuffer
import java.util.Base64

/**
 * The node property keys an embedding vector is stored under (see [ModuleEmbeddingService] and
 * [ModuleSemanticSearch]). Vectors live as ordinary `CodeModule` node properties, alongside
 * `description` -- the same mechanism [io.contextgraph.ingest.describe.ModuleDescriptionService]
 * already uses (AC-20: no separate table, no file in the source tree). Recording [MODEL] and
 * [DIMENSION] next to the vector itself is what makes a later model change detectable rather than
 * silently comparing incompatible vectors (AC-38).
 */
internal object ModuleEmbeddingProperties {
    const val VECTOR = "embeddingVector"
    const val MODEL = "embeddingModel"
    const val DIMENSION = "embeddingDimension"
    const val SOURCE_HASH = "embeddingSourceHash"
}

/**
 * Packs a `FloatArray` into a base64 string and back -- the "stored as blobs" the slice's notes
 * call for, rather than a verbose JSON array of decimal numbers (which would also bloat the FTS5
 * index that mirrors every node property, see `SqliteStorageAdapter`'s `nodes_fts`). No vector
 * database, no index: this is just a compact encoding for a value [StorageAdapter.upsertNode]
 * already knows how to persist.
 */
internal object ModuleVectorCodec {
    fun encode(vector: FloatArray): String {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
        vector.forEach { buffer.putFloat(it) }
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    fun decode(base64: String): FloatArray {
        val bytes = Base64.getDecoder().decode(base64)
        val buffer = ByteBuffer.wrap(bytes)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
    }
}
