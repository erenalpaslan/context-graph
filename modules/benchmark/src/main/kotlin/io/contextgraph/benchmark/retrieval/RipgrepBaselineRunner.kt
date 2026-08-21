package io.contextgraph.benchmark.retrieval

import java.nio.file.Path

/** [tokens] is what [RipgrepQueryDeriver] derived from the question text; [rankedFiles] is what `rg` found searching for them, ranked by matching-line count descending (ties broken alphabetically, for a fully deterministic order). */
data class RipgrepQueryOutcome(val tokens: List<String>, val rankedFiles: List<String>)

/**
 * The ripgrep side of the retrieval measurement (AC-25): runs [RipgrepQueryDeriver]'s tokens
 * against a repo's **WITHOUT** working copy -- the clean, never-indexed checkout -- exactly as a
 * human with a terminal and no ContextGraph would.
 *
 * A single `rg` invocation per question, not one per token: every token is passed as its own
 * `-e` pattern, which `rg` ORs together internally, so one pass over the tree yields one match
 * count per file for "matched at least one of these patterns" -- the natural ranking signal a
 * human skimming `rg`'s output would use ("this file has more hits, it's probably the one").
 * `-F` (fixed-strings, no regex metacharacter surprises from a dot or slash inside a token) and
 * `-w` (whole-word match, so a search for `Next` doesn't also match `NextFunc`) keep the search
 * literal and precise, matching what a token that came out of [RipgrepQueryDeriver] is meant to
 * represent -- an exact symbol/file/constant, not a fuzzy substring. No other flags are passed:
 * `rg` respects the checkout's own `.gitignore` and skips hidden/binary files by default, which
 * is exactly the behavior a human typing plain `rg` at the repo root would get -- overriding it
 * would be baseline-strengthening or -weakening by hand, the thing AC-25 forbids.
 */
class RipgrepBaselineRunner(private val rgPath: String = "rg") {

    fun rankedFiles(questionText: String, repoRoot: Path): RipgrepQueryOutcome {
        val tokens = RipgrepQueryDeriver.deriveTokens(questionText)
        if (tokens.isEmpty()) return RipgrepQueryOutcome(tokens, emptyList())

        val args = listOf("-F", "-w", "--no-messages", "--count") +
            tokens.flatMap { listOf("-e", it) } +
            listOf(".")

        val lines = RipgrepProcess.run(args, cwd = repoRoot, rgPath = rgPath)

        val ranked = lines
            .mapNotNull(::parseCountLine)
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .map { it.first }

        return RipgrepQueryOutcome(tokens, ranked)
    }

    /** Parses an `rg --count` output line (`<path>:<count>`) into (path with `./` stripped, count). Paths never contain `:`, so splitting on the last `:` is safe. */
    private fun parseCountLine(line: String): Pair<String, Int>? {
        val separator = line.lastIndexOf(':')
        if (separator < 0) return null
        val path = line.substring(0, separator).removePrefix("./")
        val count = line.substring(separator + 1).toIntOrNull() ?: return null
        return path to count
    }
}
