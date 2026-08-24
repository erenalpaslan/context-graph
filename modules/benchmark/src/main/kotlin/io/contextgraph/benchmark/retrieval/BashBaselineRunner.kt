package io.contextgraph.benchmark.retrieval

import java.nio.file.Path

/** [tokens] is what [RipgrepQueryDeriver] derived from the question text -- the *same* list the ripgrep side is given; [rankedFiles] is what base-system `grep` found searching for them, ranked by matching-line count descending (ties broken alphabetically, for a fully deterministic order). */
data class BashQueryOutcome(val tokens: List<String>, val rankedFiles: List<String>)

/**
 * The bash side of the retrieval measurement, and the one the brief is actually about: what a
 * developer gets from a question when all they have is a shell -- *"contextgraph/codegraph/bash
 * command (without thirdparty)"*. No `rg`, no installed tooling, only binaries present on a stock
 * macOS or Linux base system.
 *
 * It exists because the ripgrep side is not the floor it was taken for. `rg` is a separate
 * install, so that side answers "what does a developer who already installed ripgrep get?", and
 * some of its score is ripgrep's engineering -- `.gitignore` awareness, binary skipping, its own
 * ranking -- rather than plain text search. This side is the honest floor a code-graph index has
 * to beat to justify its cost.
 *
 * Like [RipgrepBaselineRunner] it runs against a repo's **WITHOUT** working copy -- the clean,
 * never-indexed checkout -- and is never blocked by any tool's indexing state.
 *
 * ## The fairness invariant
 *
 * This side must differ from [RipgrepBaselineRunner] in **the tool only**: same raw question in,
 * same derived tokens, same ranking rule, same metrics out. If it differed anywhere else the two
 * baselines would stop being readable against each other and the comparison would be worth less
 * than not running it. Concretely:
 *
 * - Tokens come from [RipgrepQueryDeriver.deriveTokens] -- the *same function*, not a fork of it.
 *   There is no second tokenizer in this package, and a test asserts both sides derive an
 *   identical token list for all 33 real gold questions.
 * - One invocation per question, not one per token: every token is passed as its own `-e` pattern,
 *   which `grep` ORs together internally, exactly as the ripgrep side does. One pass yields one
 *   matching-line count per file -- "this file has more hits, it's probably the one", the signal a
 *   human skimming the output would use.
 * - Ranking is matching-line count descending, ties alphabetical -- the identical rule, so neither
 *   side's ordering is doing work the other's is not.
 * - A question with no derivable tokens yields zero files, reported as a true zero and never
 *   padded. That is not a bug to work around: a question a human could not form a `grep` query for
 *   is a question `grep` structurally cannot help with, and saying so is the measurement.
 *
 * The flags are [BashBaselineFlags], which is also where the reasoning for each of them lives and
 * where the report generator reads them from.
 */
class BashBaselineRunner(
    private val grepPath: String = BashProcess.BASE_SYSTEM_GREP,
    private val launcher: BashCommandLauncher = BashProcess.SYSTEM
) {

    fun rankedFiles(questionText: String, repoRoot: Path): BashQueryOutcome {
        val tokens = RipgrepQueryDeriver.deriveTokens(questionText)
        if (tokens.isEmpty()) return BashQueryOutcome(tokens, emptyList())

        val args = BashBaselineFlags.FLAGS +
            tokens.flatMap { listOf("-e", it) } +
            listOf(".")

        val lines = BashProcess.run(args, cwd = repoRoot, grepPath = grepPath, launcher = launcher)

        val ranked = lines
            .mapNotNull(::parseCountLine)
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .map { it.first }

        return BashQueryOutcome(tokens, ranked)
    }

    /**
     * Parses a `grep -rc` output line (`./<path>:<count>`) into (path with `./` stripped, count),
     * dropping files that matched nothing.
     *
     * The zero-drop is not a filtering choice, it is what makes the two sides comparable: `rg
     * --count` omits non-matching files from its output entirely, while `grep -c` prints
     * `./other.go:0` for every file it scanned. Keeping those would hand the bash side thousands
     * of "results" that matched nothing.
     *
     * Paths never contain `:`, so splitting on the last `:` is safe -- the same reasoning
     * [RipgrepBaselineRunner] parses its own count lines with.
     */
    private fun parseCountLine(line: String): Pair<String, Int>? {
        val separator = line.lastIndexOf(':')
        if (separator < 0) return null
        val path = line.substring(0, separator).removePrefix("./")
        val count = line.substring(separator + 1).toIntOrNull() ?: return null
        if (count == 0) return null
        return path to count
    }
}
