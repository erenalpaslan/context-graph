package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.cli.RepoRoot
import io.contextgraph.benchmark.questions.QuestionSetLoader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.opentest4j.TestAbortedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * The bash side of the retrieval measurement (AC-1..AC-5): base-system shell tools only, never
 * `rg`, never anything a developer had to install.
 *
 * Unlike [RipgrepBaselineRunnerTest], which skips itself when `rg` is absent, the end-to-end tests
 * here never skip -- `grep` is present on every macOS and Linux base system, which is precisely
 * why this side is the honest floor and the ripgrep side is not.
 *
 * The argv-level tests go through [BashCommandLauncher], the seam AC-1 names: a fake that records
 * every command the runner would have spawned. That is the only way to assert "the program was
 * `grep` and never `rg`" and "these exact flags were used" as facts about the executed command
 * rather than as claims about the source.
 */
class BashBaselineRunnerTest : FunSpec({

    /** Records every argv the runner spawns and replays a canned result. Exit 1 = "ran clean, matched nothing". */
    class RecordingLauncher(
        private val result: BashCommandResult = BashCommandResult(exitCode = 1, stdout = "", stderr = "")
    ) : BashCommandLauncher {
        val invocations = mutableListOf<List<String>>()
        override fun launch(command: List<String>, cwd: Path): BashCommandResult {
            invocations += command
            return result
        }
    }

    /**
     * The question this suite's fixture is built around, and the tokens [RipgrepQueryDeriver]
     * actually derives from it -- written out as literals rather than recomputed, so a change in
     * the deriver surfaces here as a failure instead of passing by construction. Note it derives
     * *two* tokens: the dotted prose form `Engine.ServeHTTP` as well as the bare `ServeHTTP`.
     */
    val question = "What does Engine.ServeHTTP call?"
    val questionTokens = listOf("Engine.ServeHTTP", "ServeHTTP")

    fun repoRoot(): Path = RepoRoot.find(Path.of(System.getProperty("user.dir")))

    /**
     * A tiny offline checkout, not the real corpus: three matching files (one with three matching
     * lines, two with one each, so both halves of the ranking rule are exercised), one file that
     * matches nothing, a `.git` entry that matches, and a binary file that matches.
     */
    fun fixture(): Path {
        val root = Files.createTempDirectory("bash-baseline-fixture-")
        root.resolve("gin.go").writeText(
            "package gin\n\nfunc (engine *Engine) ServeHTTP(w, r) {\n" +
                "\tServeHTTP(w, r)\n}\n// ServeHTTP again\n"
        )
        root.resolve("bbb.go").writeText("package gin\n// calls ServeHTTP once\n")
        root.resolve("aaa.go").writeText("package gin\n// calls ServeHTTP once\n")
        root.resolve("other.go").writeText("package gin\n\nfunc unrelated() {}\n")
        root.resolve(".git").createDirectories()
        root.resolve(".git/packfile").writeText("ServeHTTP lives in a packfile too\n")
        root.resolve("blob.bin").writeBytes("ServeHTTP".toByteArray() + byteArrayOf(0) + "tail".toByteArray())
        return root
    }

    fun <T> withFixture(block: (Path) -> T): T {
        val root = fixture()
        try {
            return block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // --- AC-1: base-system binaries only, never rg -------------------------------------------

    test("AC-1: the spawned program is a base-system grep and never rg") {
        val launcher = RecordingLauncher()
        BashBaselineRunner(launcher = launcher).rankedFiles(question, Path.of("."))

        launcher.invocations.shouldNotBeEmpty()
        launcher.invocations.forEach { argv ->
            val program = Path.of(argv.first()).name
            program shouldBe "grep"
            program shouldNotBe "rg"
        }
    }

    test("AC-1: the base-system grep is resolved by absolute path, not through PATH") {
        // A developer's PATH may put a Homebrew or nix GNU grep ahead of /usr/bin/grep. Measuring
        // that would silently be measuring an installed third-party tool again -- the exact flaw
        // this side exists to remove from the ripgrep baseline.
        Path.of(BashProcess.BASE_SYSTEM_GREP).isAbsolute shouldBe true
        Files.isExecutable(Path.of(BashProcess.BASE_SYSTEM_GREP)) shouldBe true
    }

    // --- AC-5: the exact flag set, asserted so it cannot drift silently -----------------------

    test("AC-5: the executed argv is exactly BashBaselineFlags.FLAGS, then one -e per token, then '.'") {
        val launcher = RecordingLauncher()
        BashBaselineRunner(launcher = launcher)
            .rankedFiles("What does Engine.ServeHTTP call in gin.go?", Path.of("."))

        val argv = launcher.invocations.single()
        val tokens = RipgrepQueryDeriver.deriveTokens("What does Engine.ServeHTTP call in gin.go?")
        argv.drop(1) shouldContainExactly
            BashBaselineFlags.FLAGS + tokens.flatMap { listOf("-e", it) } + listOf(".")
    }

    test("AC-5: .git is excluded and binary files are skipped, by flag, on every invocation") {
        val launcher = RecordingLauncher()
        BashBaselineRunner(launcher = launcher).rankedFiles(question, Path.of("."))

        val argv = launcher.invocations.single()
        argv.contains("--exclude-dir=.git") shouldBe true
        argv.contains("-I") shouldBe true
    }

    test("AC-5: every flag the report will print is a flag the runner actually passes") {
        // BashBaselineFlags is what the report generator reads. If its rationale ever described a
        // flag the runner does not use -- or omitted one it does -- the published justification
        // would be for a search that never ran.
        BashBaselineFlags.RATIONALE.keys.toList() shouldContainExactly BashBaselineFlags.FLAGS
        BashBaselineFlags.RATIONALE.values.forEach { it.isNotBlank() shouldBe true }
        BashBaselineFlags.describeArgv() shouldContain BashBaselineFlags.FLAGS.joinToString(" ")
    }

    // --- AC-3: ranking and determinism ---------------------------------------------------------

    test("AC-3: files rank by matching-line count descending, ties broken alphabetically") {
        val launcher = RecordingLauncher(
            BashCommandResult(
                exitCode = 0,
                stdout = "./bbb.go:1\n./other.go:0\n./gin.go:3\n./aaa.go:1\n./ccc.go:2\n",
                stderr = ""
            )
        )
        val outcome = BashBaselineRunner(launcher = launcher)
            .rankedFiles(question, Path.of("."))

        // gin.go (3) > ccc.go (2) > aaa.go, bbb.go (1 each, alphabetical). other.go matched
        // nothing: `grep -c` prints a 0 line for it where `rg --count` would omit the file.
        outcome.rankedFiles shouldContainExactly listOf("gin.go", "ccc.go", "aaa.go", "bbb.go")
    }

    test("AC-3: end to end against the real base-system grep, .git and binaries stay out") {
        withFixture { root ->
            val outcome = BashBaselineRunner().rankedFiles(question, root)

            outcome.tokens shouldContainExactly questionTokens
            outcome.rankedFiles shouldContainExactly listOf("gin.go", "aaa.go", "bbb.go")
        }
    }

    test("AC-3: deterministic -- the same question against the same checkout ranks identically") {
        withFixture { root ->
            val runner = BashBaselineRunner()
            val first = runner.rankedFiles(question, root)
            val second = runner.rankedFiles(question, root)
            first shouldBe second
        }
    }

    // --- AC-4: exit-code semantics -------------------------------------------------------------

    test("AC-4: a clean no-match run returns an empty list -- a real zero, not an error") {
        val launcher = RecordingLauncher(BashCommandResult(exitCode = 1, stdout = "", stderr = ""))
        val outcome = BashBaselineRunner(launcher = launcher)
            .rankedFiles(question, Path.of("."))

        outcome.tokens shouldContainExactly questionTokens
        outcome.rankedFiles.shouldBeEmpty()
    }

    test("AC-4: any other exit status throws a named exception rather than scoring as zero") {
        val launcher = RecordingLauncher(
            BashCommandResult(exitCode = 2, stdout = "", stderr = "grep: unrecognized option")
        )
        val thrown = shouldThrow<BashCommandExecutionException> {
            BashBaselineRunner(launcher = launcher).rankedFiles(question, Path.of("."))
        }
        thrown.message!! shouldContain "exit 2"
        thrown.message!! shouldContain "unrecognized option"
    }

    test("AC-4: a real grep against a path that does not exist fails loudly") {
        val thrown = shouldThrow<BashCommandExecutionException> {
            BashBaselineRunner().rankedFiles(question, Path.of("/nonexistent-checkout"))
        }
        thrown.message!!.isNotBlank() shouldBe true
    }

    // --- AC-2: same tokens as the ripgrep side, and only one tokenizer -------------------------

    test("AC-2: for all 33 real gold questions the bash side searches exactly the ripgrep side's tokens") {
        val questions = QuestionSetLoader.loadDirectory(repoRoot().resolve("modules/benchmark/questions"))
        questions.size shouldBe 33

        questions.forEach { question ->
            val expected = RipgrepQueryDeriver.deriveTokens(question.text)
            val launcher = RecordingLauncher()
            val outcome = BashBaselineRunner(launcher = launcher).rankedFiles(question.text, Path.of("."))

            // What it reports it searched for...
            outcome.tokens shouldContainExactly expected
            if (expected.isEmpty()) {
                // ...and a question no grep query can be formed for is a true zero, never padded.
                launcher.invocations.shouldBeEmpty()
                outcome.rankedFiles.shouldBeEmpty()
            } else {
                // ...and what it actually put on grep's command line.
                val argv = launcher.invocations.single()
                Path.of(argv.first()).name shouldBe "grep"
                val onArgv = argv.windowed(2).filter { it[0] == "-e" }.map { it[1] }
                onArgv shouldContainExactly expected
            }
        }
    }

    test("AC-2: both real runners, side by side, differ in the tool and in nothing else") {
        // The strongest form of the fairness invariant, and the only one that exercises *both*
        // runners rather than one runner and the function the other is documented to call. Needs
        // `rg`, so it skips the way RipgrepBaselineRunnerTest does -- the weaker, always-running
        // form above is what guarantees AC-2 is covered on a machine without ripgrep.
        if (!RipgrepProcess.isAvailable("rg")) {
            throw TestAbortedException("Skipped: 'rg' not found on PATH -- install ripgrep to run this test.")
        }
        val questions = QuestionSetLoader.loadDirectory(repoRoot().resolve("modules/benchmark/questions"))
        withFixture { root ->
            val bash = BashBaselineRunner()
            val ripgrep = RipgrepBaselineRunner()
            questions.forEach { q ->
                val fromBash = bash.rankedFiles(q.text, root)
                val fromRipgrep = ripgrep.rankedFiles(q.text, root)
                withClue("question ${q.id}") {
                    fromBash.tokens shouldContainExactly fromRipgrep.tokens
                    // Same tokens, same ranking rule, same checkout -- so on a tree with nothing
                    // for `rg`'s extra defaults to act on (no .gitignore, no hidden file either
                    // side keeps, no binary either side reads) the two sides must agree exactly.
                    // Where they diverge on the real corpus, the tool is the only thing that can
                    // explain it, which is what makes the two columns readable against each other.
                    fromBash.rankedFiles shouldContainExactly fromRipgrep.rankedFiles
                }
            }
        }
    }

    test("AC-2: no second tokenizer exists anywhere in io.contextgraph.benchmark.retrieval") {
        val packageDir = repoRoot()
            .resolve("modules/benchmark/src/main/kotlin/io/contextgraph/benchmark/retrieval")
        val definers = Files.walk(packageDir).use { paths ->
            paths.filter { it.name.endsWith(".kt") }
                .filter { it.readText().contains("fun deriveTokens") }
                .map { it.name }
                .toList()
        }
        definers shouldContainExactly listOf("RipgrepQueryDeriver.kt")
    }
})
