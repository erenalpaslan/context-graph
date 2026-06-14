package io.contextgraph.ingest

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.inputStream

class ChecksumTracker {
    fun checksum(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
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
