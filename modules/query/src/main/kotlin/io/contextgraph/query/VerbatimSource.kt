package io.contextgraph.query

import io.contextgraph.core.Provenance
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the exact source text a [Provenance]'s recorded line range points at, straight off
 * disk -- never reconstructed from node properties or regenerated. Slice 17's task notes call
 * this the single highest-value field the `explore` MCP tool returns: everything else the graph
 * knows about a symbol is a pointer an agent would otherwise have to follow with a separate file
 * read.
 */
object VerbatimSource {
    /**
     * Null when [provenance] has no recorded line range, the file it points at cannot be found
     * at [projectRoot], or the recorded range no longer fits the file on disk (e.g. it shrank
     * since the graph was last indexed) -- callers treat null the same as "nothing to show",
     * never as an error.
     */
    fun read(projectRoot: Path, provenance: Provenance): String? {
        val start = provenance.lineStart ?: return null
        val end = (provenance.lineEnd ?: start).coerceAtLeast(start)
        val file = projectRoot.resolve(provenance.path)
        if (!Files.isRegularFile(file)) return null
        return try {
            val lines = Files.readAllLines(file)
            if (start < 1 || start > lines.size) return null
            lines.subList(start - 1, end.coerceAtMost(lines.size)).joinToString("\n")
        } catch (_: Exception) {
            null
        }
    }
}
