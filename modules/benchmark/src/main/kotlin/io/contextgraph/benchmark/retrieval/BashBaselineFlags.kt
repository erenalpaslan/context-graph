package io.contextgraph.benchmark.retrieval

/**
 * The exact `grep` flag set the bash side runs with, and the one-line reason each flag is there.
 *
 * **This object exists to be read by the report generator** (AC-5: "the generated report states
 * the exact flags used and why"). The flags are a value here rather than a string literal buried
 * inside [BashBaselineRunner], because a reader deciding whether the baseline was fair has to be
 * able to see them, and because a flag list that lives in two places drifts. [BashBaselineRunner]
 * builds its argv from [FLAGS] directly, so the report cannot describe a search the runner did not
 * actually perform.
 *
 * ## Why these flags, and why not the ripgrep side's
 *
 * The ripgrep side passes `-F -w --no-messages --count` and nothing else, reasoning that `rg`'s
 * *defaults* -- honouring the checkout's `.gitignore`, skipping hidden and binary files -- are
 * what a human typing plain `rg` at a repo root actually gets, and that overriding them would be
 * hand-tuning the baseline.
 *
 * `grep` has no such defaults, so honesty here means a different flag list, not the same one. Each
 * flag below either states something `rg` assumes, or states nothing at all:
 *
 * - `-r`, `-F`, `-w`, `-c` are the literal equivalents of `rg`'s recursion, `-F`, `-w` and
 *   `--count`. Nothing is added or withheld.
 * - `-I` and `--exclude-dir=.git` say out loud what `rg` does silently. They **narrow** the gap
 *   between the two sides rather than widening it: `rg` skips binary files and hidden entries
 *   (`.git` included) by default, so a `grep` without these two would be searching strictly more
 *   than `rg` does, and a depth-1 mirror's packfiles would dominate both the runtime and the match
 *   counts -- measuring the harness instead of the baseline.
 * - `-s` is `--no-messages`, flag-for-flag.
 *
 * `-r` rather than `-R` is also load-bearing: both BSD and GNU `grep` decline to follow symbolic
 * links found inside the tree under `-r`, verified on macOS's BSD grep 2.6.0-FreeBSD against a
 * checkout containing a self-referential link. `-R` would follow them, and one symlink loop in a
 * real repo would either hang the side or count the same file many times.
 *
 * What is deliberately **not** here is any emulation of `rg`'s `.gitignore` awareness. That is
 * `rg`'s engineering, not text search's, and reproducing it with `--exclude-dir` lists would be
 * quietly strengthening the bash side into a second ripgrep -- as dishonest as starving it.
 * *Baseline'ı zayıflatarak kazanılan bir sayı, kazanılmamış sayıdır* -- and a number won by
 * secretly strengthening the baseline is not the baseline's number either.
 */
object BashBaselineFlags {

    /**
     * The flags, in the exact order [BashBaselineRunner] passes them, before the `-e <token>` pairs
     * and the trailing search path.
     */
    val FLAGS: List<String> = listOf("-r", "-F", "-w", "-I", "-s", "--exclude-dir=.git", "-c")

    /** Each flag in [FLAGS], in the same order, mapped to the one line a report should print beside it. */
    val RATIONALE: Map<String, String> = linkedMapOf(
        "-r" to "Search the checkout recursively -- `rg` recurses by default; `grep` has to be told.",
        "-F" to "Fixed strings, so a dot or slash inside a derived token is not a regex metacharacter. Same as the ripgrep side's -F.",
        "-w" to "Whole-word match, so searching for `Next` does not also match `NextFunc`. Same as the ripgrep side's -w.",
        "-I" to "Skip binary files, which `rg` does by default; without it `grep` prints `Binary file … matches` instead of a count.",
        "-s" to "Suppress messages about unreadable files. Flag-for-flag equivalent of the ripgrep side's --no-messages.",
        "--exclude-dir=.git" to "Exclude the repository's own `.git` directory, which `rg` skips by default as a hidden entry; a depth-1 mirror's packfiles would otherwise dominate both runtime and match counts.",
        "-c" to "Count matching lines per file -- the ranking signal. Same as the ripgrep side's --count."
    )

    /** The whole argv shape, for a report that wants to show the command rather than list flags. */
    fun describeArgv(): String =
        "grep ${FLAGS.joinToString(" ")} -e <token> [-e <token> …] ."
}
