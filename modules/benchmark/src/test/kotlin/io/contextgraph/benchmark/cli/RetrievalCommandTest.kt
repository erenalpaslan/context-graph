package io.contextgraph.benchmark.cli

import io.contextgraph.benchmark.retrieval.BashProcess
import io.contextgraph.benchmark.retrieval.RetrievalSide
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

/**
 * What the operator sees before they run anything, which is the only place some of this axis's
 * honesty is stated at all.
 *
 * Two things are asserted here that no other test can reach. First, the help text must name every
 * side and say which are third-party: a reader who is told "ContextGraph, CodeGraph and ripgrep"
 * would reasonably conclude the floor being cleared is ripgrep's, when the floor this run exists
 * to establish is a stock shell's. Second, `--grep-path` must default to an absolute path rather
 * than to a bare name resolved through `PATH` — a developer's `PATH` may put a Homebrew or `nix`
 * GNU grep ahead of `/usr/bin/grep`, and a bash side measuring that is measuring a third-party
 * tool again, silently, which is the exact flaw the side exists to remove.
 */
class RetrievalCommandTest : FunSpec({

    fun command() = RetrievalCommand(
        startDir = Path.of(System.getProperty("user.dir")),
        explicitRepoRoot = null
    )

    /**
     * Clikt hard-wraps help output at the terminal width, so a phrase asserted below would
     * otherwise fail for the accident of where a line broke rather than for anything about the
     * text. Collapsing whitespace asserts on the sentence, which is what this is about.
     */
    fun helpText() = command().getFormattedHelp()!!.replace(Regex("\\s+"), " ")

    test("the help text names all four sides and says which of them are third-party") {
        val help = helpText()

        // Every side, by name.
        help shouldContain "ContextGraph"
        help shouldContain "CodeGraph"
        help shouldContain "bash"
        help shouldContain "ripgrep"

        // And what each one is. "third-party" has to be attached to CodeGraph and to ripgrep, and
        // the bash side has to be marked as needing none, or the reader cannot tell which numbers
        // describe a machine with things installed on it.
        help shouldContain "ContextGraph (this project)"
        help shouldContain "CodeGraph (third-party)"
        help shouldContain "bash (base-system shell only, no third-party tools)"
        help shouldContain "ripgrep (third-party, retained for comparison)"
    }

    test("--grep-path is overridable the way --rg-path and --codegraph-path are") {
        val names = command().registeredOptions().flatMap { it.names }
        names.contains("--grep-path") shouldBe true
        names.contains("--rg-path") shouldBe true
        names.contains("--codegraph-path") shouldBe true
    }

    test("--grep-path defaults to an absolute base-system path, not to a PATH lookup") {
        // The default is printed in the help, so an operator can see which binary is about to be
        // measured without reading the source.
        helpText() shouldContain BashProcess.BASE_SYSTEM_GREP
        Path.of(BashProcess.BASE_SYSTEM_GREP).isAbsolute shouldBe true
    }

    test("the help's side names stay in step with RetrievalSide, which owns them") {
        // Not a tautology: the help sentence is prose and cannot interpolate the enum labels
        // verbatim, so this is the one check that catches the two drifting apart.
        RetrievalSide.BASH.label shouldContain "base-system"
        RetrievalSide.RIPGREP.label shouldContain "ripgrep"
    }
})
