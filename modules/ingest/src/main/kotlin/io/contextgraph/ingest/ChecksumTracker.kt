package io.contextgraph.ingest

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.inputStream

/**
 * Bump when an extractor starts recording something it did not before, so that the next index
 * re-extracts every file once instead of keeping each unchanged file's old output.
 *
 * Pass 1's output for a file is skipped when the file's checksum matches the stored one. That
 * is right while extraction is fixed, and wrong across an upgrade that changes it: a repo whose
 * files have not changed would keep references recorded without the new receiver hints until
 * each file happened to be edited. Mixing this version into the checksum makes every stored
 * checksum stale exactly once, on the first index after the upgrade.
 *
 * 2: Kotlin records declared types, return types, supertypes and receiver hints.
 */
internal const val EXTRACTION_FORMAT_VERSION = 2

class ChecksumTracker {
    fun checksum(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("extraction-format-$EXTRACTION_FORMAT_VERSION\n".toByteArray())
        path.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytes = input.read(buffer)
            while (bytes >= 0) {
                digest.update(buffer, 0, bytes)
                bytes = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
