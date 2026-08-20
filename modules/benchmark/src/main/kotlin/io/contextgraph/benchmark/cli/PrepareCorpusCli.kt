package io.contextgraph.benchmark.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.contextgraph.benchmark.corpus.CorpusCatalog
import io.contextgraph.benchmark.corpus.CorpusPreparationStep
import java.nio.file.Path

/**
 * Standalone entry point for corpus preparation (AC-1, AC-1a, AC-2): clone-or-verify each
 * pinned repo's two working copies and index the WITH copy. Wired as a separate
 * `CliktCommand`/`main()` from [BenchmarkCli] rather than a subcommand of it, because
 * [BenchmarkCli]'s `--profile` is `required()` on the parent command — Clikt finalizes a
 * parent's own required options before ever dispatching to a subcommand, so nesting this
 * under `BenchmarkCli` would force every corpus-prep invocation to also pass a meaningless
 * `--profile`. This command calls the exact same `io.contextgraph.benchmark.corpus` building
 * blocks slice 12's orchestrator will call later ([CorpusPreparationStep.run]); it does not
 * reimplement anything from that package.
 *
 * [startDir] and [explicitRepoRoot] are required constructor parameters, not read inline in
 * [run] and deliberately given no default. An earlier version defaulted them to
 * `Path.of(System.getProperty("user.dir"))` / `System.getProperty(RepoRoot.REPO_ROOT_PROPERTY)
 * ?.let(Path::of)` directly on the constructor — but no test ever constructs this command
 * bare, so those two default-argument expressions were never actually executed by any test;
 * worse, Gradle's `Test` task working directory defaults to this module's own directory and
 * the property is unset in the test JVM, both of which happen to coincide with the values a
 * wiring test would inject deliberately, so even a bare-construction test could pass while a
 * broken default silently matched the ambient environment by coincidence. Requiring both
 * parameters removes the seam instead of trying to test around it: [main] is now the only
 * place that reads `user.dir` / [RepoRoot.REPO_ROOT_PROPERTY], and it does so as one thin,
 * deliberately-uncovered line — the entry point's job, not this class's. See
 * `PrepareCorpusCommandTest` for the (still-covered) proof that this class feeds whatever
 * [startDir]/[explicitRepoRoot] it's given through to [RepoRoot.resolveCorpusRoot] correctly.
 */
class PrepareCorpusCommand(
    private val startDir: Path,
    private val explicitRepoRoot: Path?
) : CliktCommand(name = "prepare-corpus") {
    override fun help(context: Context) =
        "Clone-or-verify the pinned corpus repos' two working copies (WITH/WITHOUT) and index " +
            "the WITH copy with litellm.enabled=false. Reused as-is by slice 12's orchestrator."

    private val corpusRoot by option(
        "--corpus-root",
        help = "Directory the corpus is prepared under (gitignored, outside version control). " +
            "A relative value (including the default) resolves against the repository root, " +
            "not the caller's working directory — see RepoRoot. Default: .benchmark-corpus"
    ).default(".benchmark-corpus")

    private val repoIds by option(
        "--repos",
        help = "Comma-separated subset/order of repo ids to prepare (default: all four, catalog order). " +
            "Known ids: ${CorpusCatalog.DEFAULT.joinToString(", ") { it.id }}"
    )

    /**
     * The corpus root resolved from the parsed `--corpus-root`, [startDir] and
     * [explicitRepoRoot]. `null` until [run] has resolved it at least once; exposed
     * (`internal`, test-only) so a wiring test can assert on it directly instead of racing
     * [run]'s later, real corpus preparation.
     */
    internal var resolvedCorpusRoot: Path? = null
        private set

    override fun run() {
        val root = RepoRoot.resolveCorpusRoot(corpusRoot, startDir, explicitRepoRoot)
        resolvedCorpusRoot = root
        val repos = repoIds
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map { id ->
                CorpusCatalog.DEFAULT.find { it.id == id }
                    ?: throw IllegalArgumentException(
                        "Unknown repo id '$id'; known ids: ${CorpusCatalog.DEFAULT.joinToString(", ") { it.id }}"
                    )
            }
            ?: CorpusCatalog.DEFAULT

        echo("Preparing ${repos.size} repo(s) under $root: ${repos.joinToString(", ") { it.id }}")

        val results = CorpusPreparationStep.run(root, repos = repos)

        results.forEach { result ->
            val repo = result.repo
            val ingest = result.ingestRecord
            echo(
                "${repo.id}: pinned=${repo.pinnedTag}@${repo.pinnedSha} " +
                    "with=${repo.workingCopyWithPath} without=${repo.workingCopyWithoutPath} " +
                    "ingest=" + (ingest?.let { "${it.durationMillis}ms tokens=${it.tokensUsed} costUsd=${it.costUsd}" } ?: "not built")
            )
        }

        echo("Corpus preparation complete: ${results.size}/${repos.size} repo(s) ready.")
    }
}

// The only two reads of the real launch context (user.dir / REPO_ROOT_PROPERTY) in this file —
// deliberately isolated here, in the entry point, rather than defaulted on the constructor.
// Uncovered by a unit test on purpose: see PrepareCorpusCommand's KDoc for why a default here
// would be an untestable seam, not a tested one.
fun main(args: Array<String>) = PrepareCorpusCommand(
    startDir = Path.of(System.getProperty("user.dir")),
    explicitRepoRoot = System.getProperty(RepoRoot.REPO_ROOT_PROPERTY)?.let(Path::of)
).main(args)
