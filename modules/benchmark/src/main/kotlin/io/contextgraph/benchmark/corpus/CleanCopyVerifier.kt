package io.contextgraph.benchmark.corpus

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/** [root] contains one or more ContextGraph artifacts; [found] names them, relative to [root]. */
class CleanCopyContaminatedException(val root: Path, val found: List<String>) :
    RuntimeException(
        "Control-arm working copy at $root is contaminated with ContextGraph artifact(s): " +
            "${found.joinToString(", ")} — the WITHOUT arm must be a checkout ContextGraph has " +
            "never touched (spec AC-7a)."
    )

/**
 * Proves a working copy represents "a project ContextGraph has never touched" (spec AC-7a),
 * which is the structural reason the control arm has to be a *separate* checkout rather than
 * the same directory with MCP tools switched off: `GraphDb` always writes to
 * `<root>/.contextgraph/graph.db`, so a directory that was ever indexed carries that artifact
 * forever, and the only way to guarantee absence is to never index the directory at all.
 */
object CleanCopyVerifier {

    /** Every artifact name AC-7a explicitly lists, checked at [root]'s top level. */
    val KNOWN_ARTIFACT_NAMES = listOf(".contextgraph", "graph.db", "graph.local.db", "GRAPH_REPORT.md", "graph.html")

    /** Which of [KNOWN_ARTIFACT_NAMES] exist directly under [root], if any. */
    fun findArtifacts(root: Path): List<String> = KNOWN_ARTIFACT_NAMES.filter { root.resolve(it).exists() }

    /** Throws [CleanCopyContaminatedException] if [root] contains any known ContextGraph artifact. */
    fun verifyClean(root: Path) {
        val found = findArtifacts(root)
        if (found.isNotEmpty()) throw CleanCopyContaminatedException(root, found)
    }

    /**
     * A stable digest over every tracked file's relative path and content under [root],
     * skipping `.git` (worktree-internal, not part of "the checkout" the WITHOUT arm exposes
     * to an agent, and mutated by git itself as part of normal worktree bookkeeping). Two
     * calls returning the same digest is the "bit-for-bit unchanged" proof the task's
     * acceptance criteria ask for — a plain "no known artifact present" check alone wouldn't
     * catch e.g. a stray byte written into an existing tracked file.
     */
    fun fingerprint(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(root).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .filter { !it.relativeTo(root).toString().startsWith(".git") }
                .sorted()
                .forEach { file ->
                    digest.update(file.relativeTo(root).toString().toByteArray())
                    digest.update(Files.readAllBytes(file))
                }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
