package io.contextgraph.benchmark.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Mandatory evidence for a [GoldFact]: the exact file and line the fact is
 * grounded in. AC-4 requires every gold fact to carry a `file:line` citation
 * — the type system enforces that here, not by convention: [GoldFact.evidence]
 * is non-optional, and both direct construction and JSON/YAML deserialization
 * reject anything that isn't a well-formed "file:line" string.
 */
@Serializable(with = EvidenceSerializer::class)
data class Evidence(val file: String, val line: Int) {
    init {
        require(file.isNotBlank()) { "Evidence file path must not be blank" }
        require(line > 0) { "Evidence line must be a positive integer, was $line" }
    }

    override fun toString(): String = "$file:$line"

    companion object {
        private val FORMAT = Regex("^(.+):(\\d+)$")

        /**
         * Parses a `dosya:satır` / "file:line" string such as `src/Foo.kt:42`.
         * Throws [IllegalArgumentException] with a message identifying the
         * malformed input if the string isn't in that shape — callers that
         * load a whole question set (slice 03) are expected to catch this and
         * enrich the message with the owning gold fact's id.
         */
        fun parse(raw: String): Evidence {
            val trimmed = raw.trim()
            val match = FORMAT.matchEntire(trimmed)
                ?: throw IllegalArgumentException(
                    "Evidence must be in 'file:line' format (e.g. 'src/Foo.kt:42'), was: '$raw'"
                )
            val line = match.groupValues[2].toIntOrNull()
                ?: throw IllegalArgumentException("Evidence line must be a positive integer, was: '$raw'")
            return Evidence(match.groupValues[1], line)
        }
    }
}

/**
 * Serializes [Evidence] as a plain `"file:line"` JSON/YAML string rather than
 * a nested object, so data files stay readable (`evidence: "src/Foo.kt:42"`).
 */
object EvidenceSerializer : KSerializer<Evidence> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Evidence", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Evidence) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Evidence {
        val raw = decoder.decodeString()
        return try {
            Evidence.parse(raw)
        } catch (e: IllegalArgumentException) {
            throw SerializationException("Invalid gold fact evidence '$raw': ${e.message}", e)
        }
    }
}
