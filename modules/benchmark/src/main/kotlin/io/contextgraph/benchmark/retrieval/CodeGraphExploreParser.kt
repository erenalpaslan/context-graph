package io.contextgraph.benchmark.retrieval

/**
 * Projects the ranked file list out of `codegraph explore`'s markdown.
 *
 * **This is the fidelity risk of the whole three-way comparison.** `explore` has no `--json`
 * flag -- its only options are `-p/--path` and `--max-files` (verified against the installed
 * v1.5.0 binary) -- so markdown is the interface, and a parser that silently matches nothing
 * reads as "CodeGraph retrieves nothing". That is indistinguishable, in the published numbers,
 * from a real finding, and it would hand ContextGraph a win it did not earn. Hence
 * `CodeGraphExploreParserTest` and the committed fixtures of real output under
 * `src/test/resources/codegraph/` -- see that directory's README for how each was captured.
 *
 * ## What counts as a result
 *
 * The **file section headings** of the Source Code section, in the order they are emitted. The
 * heading shape is fixed in CodeGraph's own source as ``FILE_SECTION_PREFIX = "**`"`` followed
 * by the path and ``"`**"`` (`dist/mcp/tools.js:307,314-318`), optionally with an
 * `" — <symbols>"` suffix. The paths it emits are already repo-relative, which is what
 * [ExpectedFileSet] holds, so no rewriting is needed -- only a defensive `./` strip.
 *
 * Emission order *is* the ranking: this class does not sort, re-rank, or truncate. Repeats
 * collapse to their first occurrence, which preserves order. That restraint is a fairness
 * invariant of the comparison, not a stylistic choice -- the same one `RipgrepQueryDeriver`'s
 * KDoc already defends for the baseline ("baseline'ı zayıflatarak kazanılan bir sayı,
 * kazanılmamış sayıdır"), now holding across three sides instead of two.
 *
 * ## What deliberately does not count, and the honest cost of that
 *
 * The **blast-radius** section, which precedes the source and reads
 * `` - `symbol` (path:line) — N callers; tests: `path`, `path` ``. Those paths are annotations
 * about impact, and on a real query they name files the Source Code section does not: in
 * `explore-large.md`, four rendered files against seven further paths mentioned only there.
 *
 * They are excluded because the Source Code section is what CodeGraph presents *as its answer* --
 * its own header counts exactly those files ("Found 6 symbols across 4 files", driven by
 * `renderedFilePaths`, which `dist/mcp/tools.js` comments must "reflect what we show, not the
 * raw candidate gather"), and its own preamble tells the agent to treat those files as already
 * read. The brief for this work independently specified the same rule: parse file headings in
 * emission order.
 *
 * **This is an interpretive choice and it moves the numbers.** Counting blast-radius paths too
 * would raise CodeGraph's recall and lower its precision. It is recorded here, and in
 * `BENCHMARKS.md`'s methodology, so a reader can see which convention produced the figures
 * rather than having to infer it.
 *
 * ## Two shapes that are not failures
 *
 * - `No relevant code found for "<query>"` -- one line, no markdown. A real empty answer, and it
 *   returns an empty list. **An empty list here means CodeGraph ran and found nothing**; a failed
 *   invocation is a different thing entirely and is never allowed to reach this parser (see
 *   [CodeGraphRetrievalRunner], which turns that into an absent side and a recorded skip).
 * - A `### ⚠️ Low-confidence match` section. Parsing simply continues past it, because it
 *   contains no file paths -- only advisory prose and *directory* hints in backticks, which the
 *   heading shape excludes anyway. In v1.5.0 `explore` cannot even emit it (only
 *   `ContextBuilder` does, on a path the CLI does not reach); the handling is defensive, so that
 *   a future version routing `explore` through that builder does not silently truncate a
 *   ranked list at the marker.
 */
object CodeGraphExploreParser {

    /**
     * Matches one file section heading, anchored to the start of a line so that inline code spans
     * elsewhere in the prose cannot be mistaken for one. Non-greedy, so a heading carrying an
     * `" — symbols"` suffix that itself contains backticks still yields just the path.
     */
    private val FILE_HEADING = Regex("^\\*\\*`(.+?)`\\*\\*", RegexOption.MULTILINE)

    /** The exact sentinel from CodeGraph's `dist/context/markers.d.ts`, kept for the test to name. */
    const val LOW_CONFIDENCE_MARKER: String = "### ⚠️ Low-confidence match"

    /**
     * [output] is `codegraph explore`'s stdout verbatim. Returns the repo-relative paths it
     * rendered source for, in emission order, without repeats. An output naming no files -- for
     * any reason, including the "No relevant code found" line -- yields an empty list rather than
     * throwing: distinguishing "found nothing" from "could not run" is the caller's job, and
     * conflating them is the failure this whole class is written to avoid.
     */
    fun rankedFiles(output: String): List<String> =
        FILE_HEADING.findAll(output)
            .map { it.groupValues[1].trim().removePrefix("./") }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
}
