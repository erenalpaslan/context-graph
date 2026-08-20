package io.contextgraph.benchmark.runner

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * The names [GraphDb] (see `modules/core`) and the CLI's `report`/`export`/`serve-mcp` commands
 * are known to write at a project root. The path is not relocatable (see this repo's own
 * CLAUDE.md: `.contextgraph/graph.db` always lives under the project root), so any one of these
 * appearing in a working copy that is supposed to be untouched is conclusive evidence the copy
 * was indexed, or written to, by something.
 */
private val ARTEFACT_NAMES = listOf(
    ".contextgraph",
    "graph.db",
    "graph.local.db",
    "GRAPH_REPORT.md",
    "graph.html"
)

/**
 * Thrown when [WorkingCopyVerifier.verifyClean] finds a ContextGraph artefact in a directory
 * that is required to be untouched. AC-7a: the run must refuse to start, not clean up and
 * continue -- an artefact here means either the WITH/WITHOUT working copies got mixed up, or
 * something wrote into the copy that is supposed to represent "ContextGraph never existed".
 * Neither is safe to paper over.
 */
class ContaminatedWorkingCopyException(val dir: Path, val artefacts: List<String>) :
    IllegalStateException(
        "ContextGraph artefact(s) found in working copy $dir: $artefacts -- refusing to start run"
    )

/**
 * Two different questions, both answered here, deliberately kept as two different checks
 * (per review on this slice): "does this directory carry a *known-named* artefact" -- the
 * presence check ([findArtefacts]/[verifyClean]) -- versus "did this directory change at all
 * between two points in time" -- the diff check ([fingerprint]).
 *
 * [findArtefacts]/[verifyClean] alone is right for AC-7a's pre-run gate: the WITHOUT_TOOLS copy
 * must start with none of the specific artefacts `GraphDb` is known to write, and refusing to
 * start is a binary presence question.
 *
 * It is *not* right for AC-9's post-run contamination check, which the presence check used to be
 * (mis)used for. AC-9 asks whether "the block mechanism failed" in **either** arm, and presence
 * alone is blind to that in two ways: the WITH_TOOLS copy carries these artefacts by design, so
 * presence proves nothing there; and a bypassed *read-only* CLI call (e.g. `contextgraph
 * search`) leaves no artefact at all, in either arm -- it returns real graph context to the
 * agent without writing anything to disk. [fingerprint] answers the question AC-9 actually asks:
 * did *anything* in the working copy change, tracked-artefact or not, in either arm. See
 * [AgentRunner] for how the two checks are actually combined.
 */
object WorkingCopyVerifier {

    /** Top-level-only: artefacts land at the project root by construction (see file header). */
    fun findArtefacts(dir: Path): List<String> =
        ARTEFACT_NAMES.filter { Files.exists(dir.resolve(it)) }

    /** Throws [ContaminatedWorkingCopyException] if [dir] carries any known artefact. */
    fun verifyClean(dir: Path) {
        val found = findArtefacts(dir)
        if (found.isNotEmpty()) {
            throw ContaminatedWorkingCopyException(dir, found)
        }
    }

    /**
     * A stable digest over every regular file's path (relative to [dir]) and content, skipping
     * `.git` (worktree-internal bookkeeping, not part of what an agent sees or can write to in
     * the way this check cares about). Two calls returning the same digest is "nothing in this
     * directory changed" -- the same algorithm and the same proof
     * `io.contextgraph.benchmark.corpus.CleanCopyVerifier.fingerprint` uses for the adjacent
     * "is this checkout bit-for-bit unchanged" question in slice 02's package; duplicated here
     * rather than depended on so this package doesn't reach into another slice's territory for a
     * five-line primitive.
     *
     * Walks the *entire* working copy, so on a large indexed repo this is a real cost paid twice
     * per run (before and after). Correctness over performance for this slice, which measures
     * one run; if that cost matters at the 06/12 orchestration layer across repeats of the same
     * repo, caching the pre-run fingerprint per corpus-prep rather than per individual run is the
     * place to optimise it -- not here.
     */
    fun fingerprint(dir: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(dir).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .filter { !it.relativeTo(dir).toString().startsWith(".git") }
                .sorted()
                .forEach { file ->
                    digest.update(file.relativeTo(dir).toString().toByteArray())
                    digest.update(Files.readAllBytes(file))
                }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
