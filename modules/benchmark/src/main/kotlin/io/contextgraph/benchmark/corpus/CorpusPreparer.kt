package io.contextgraph.benchmark.corpus

import io.contextgraph.benchmark.model.CorpusRepo
import io.contextgraph.benchmark.runner.GraphTool
import java.nio.file.Path

/**
 * [repoId]'s checkout at [worktreePath] (labelled [role], `"with"` or `"without"`) is at
 * [actualSha] instead of the [expectedSha] the corpus catalog pins it to. Thrown instead of
 * silently re-cloning: the task's own instruction is "sessizce düzeltmez" (never silently
 * fixes it) — an operator who deliberately or accidentally moved a checkout needs to know,
 * not have their change quietly discarded.
 */
class CorpusShaMismatchException(
    val repoId: String,
    val role: String,
    val worktreePath: Path,
    val expectedSha: String,
    val actualSha: String
) : RuntimeException(
    "Corpus repo '$repoId' ($role copy at $worktreePath): expected pinned SHA $expectedSha, " +
        "found $actualSha. Not correcting automatically — remove the working copy or the " +
        "corpus root to re-clone, or confirm the new commit is the intended pin."
)

/**
 * Prepares one [CorpusRepo]'s three independent working copies (AC-1a) under [corpusRoot]:
 *
 * ```
 * <corpusRoot>/_mirrors/<id>.git   a shallow bare mirror, depth 1, pinned to the tag
 * <corpusRoot>/<id>/with/          worktree checked out at pinnedSha — ContextGraph indexes this
 * <corpusRoot>/<id>/without/       worktree checked out at pinnedSha — never touched again
 * <corpusRoot>/<id>/codegraph/     worktree checked out at pinnedSha — CodeGraph indexes this
 * ```
 *
 * The third copy exists so each tool indexes its own checkout and neither ever sees the other's
 * artifacts, leaving the never-indexed control clean for both. Its name is not chosen here:
 * [io.contextgraph.benchmark.runner.GraphTool.CODEGRAPH]'s `workingCopyDirName` is the single
 * source of truth, and `withToolsDir` resolves the same sibling path from the WITH copy's. Those
 * two agreeing is the whole point — the directory that enum resolved to had never been created
 * by anything, which is the defect this fixes.
 *
 * It is created **unconditionally**, for every repo, whether or not the CodeGraph binary is
 * installed. A worktree is cheap; making the corpus layout depend on which machine ran
 * preparation is not, and failing preparation outright because an optional third-party tool is
 * absent would break the existing single-tool workflow for everyone. Whether it then gets
 * *indexed* is [CorpusPreparationStep]'s decision, recorded either way.
 *
 * Both worktrees share the mirror's object store (`git worktree add` from one bare clone)
 * rather than being two independent full clones — same file content, half the disk cost, and
 * `git worktree add` gives genuinely independent working directories: writes to one never
 * touch the other (see `CorpusPreparerTest`'s independence test and `CleanCopyVerifier`'s
 * fingerprint proof of that in `CorpusPreparationStepTest`).
 *
 * [prepare] is idempotent: a mirror or worktree that already exists is left alone, after its
 * checked-out SHA is verified against [CorpusRepo.pinnedSha] — a mismatch throws
 * [CorpusShaMismatchException] rather than silently re-pointing the checkout (task 02's
 * explicit "sessizce düzeltmez" requirement).
 */
class CorpusPreparer {

    fun prepare(repo: CorpusRepo, corpusRoot: Path): CorpusRepo {
        val mirrorDir = mirrorDir(corpusRoot, repo.id)
        val withDir = worktreeDir(corpusRoot, repo.id, "with")
        val withoutDir = worktreeDir(corpusRoot, repo.id, "without")
        val codeGraphDir = worktreeDir(corpusRoot, repo.id, CODEGRAPH_ROLE)

        ensureMirror(repo, mirrorDir)
        ensureWorktree(repo, mirrorDir, withDir, role = "with")
        ensureWorktree(repo, mirrorDir, withoutDir, role = "without")
        ensureWorktree(repo, mirrorDir, codeGraphDir, role = CODEGRAPH_ROLE)

        return repo.copy(
            workingCopyWithPath = withDir.toAbsolutePath().normalize().toString(),
            workingCopyWithoutPath = withoutDir.toAbsolutePath().normalize().toString()
        )
    }

    private fun ensureMirror(repo: CorpusRepo, mirrorDir: Path) {
        if (GitOps.isGitDir(mirrorDir)) return
        GitOps.cloneMirror(repo.url, mirrorDir, repo.pinnedTag)
        val mirrorSha = GitOps.revParseHead(mirrorDir)
        check(mirrorSha == repo.pinnedSha) {
            "Corpus repo '${repo.id}': tag '${repo.pinnedTag}' resolves to $mirrorSha on the " +
                "remote, not the pinned SHA ${repo.pinnedSha}. The catalog entry is stale " +
                "(the tag was force-moved, or the wrong tag/SHA pair was recorded) — update " +
                "CorpusCatalog rather than proceeding on a mismatched pin."
        }
    }

    private fun ensureWorktree(repo: CorpusRepo, mirrorDir: Path, worktreeDir: Path, role: String) {
        if (GitOps.isGitDir(worktreeDir)) {
            val actualSha = GitOps.revParseHead(worktreeDir)
            if (actualSha != repo.pinnedSha) {
                throw CorpusShaMismatchException(repo.id, role, worktreeDir, repo.pinnedSha, actualSha)
            }
            return
        }
        GitOps.worktreeAdd(mirrorDir, worktreeDir, repo.pinnedSha)
    }

    companion object {
        /**
         * The third working copy's directory name, taken from the tool enum rather than written
         * out here, so this and [io.contextgraph.benchmark.runner.GraphTool.withToolsDir] cannot
         * drift into naming two different directories.
         */
        val CODEGRAPH_ROLE: String = GraphTool.CODEGRAPH.workingCopyDirName

        fun mirrorDir(corpusRoot: Path, repoId: String): Path =
            corpusRoot.resolve("_mirrors").resolve("$repoId.git")

        fun worktreeDir(corpusRoot: Path, repoId: String, role: String): Path =
            corpusRoot.resolve(repoId).resolve(role)
    }
}
