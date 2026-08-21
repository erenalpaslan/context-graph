package io.contextgraph.benchmark.cli

import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/**
 * Closes the wiring gap the pure [RepoRootTest] leaves open: that test only proves
 * [RepoRoot.resolveCorpusRoot] is correct given the right arguments — it says nothing about
 * whether [PrepareCorpusCommand] actually *feeds* it the right arguments. This test exercises
 * the real constructor-to-[RepoRoot] wiring end to end, with no `CorpusPreparationStep` call
 * (an unknown `--repos` id trips [PrepareCorpusCommand.run] right after root resolution but
 * before any cloning or indexing, so this test never touches the filesystem beyond that).
 */
class PrepareCorpusCommandTest : FunSpec({

    val repoRoot = RepoRoot.find(Path.of(System.getProperty("user.dir")))

    test(
        "PrepareCorpusCommand resolves --corpus-root against the repo root reached from its " +
            "startDir constructor parameter, not against startDir itself (regression for the " +
            "original cwd-relative defect, at the wiring layer rather than the pure function)"
    ) {
        val moduleShapedStartDir = repoRoot.resolve("modules/benchmark")
        val command = PrepareCorpusCommand(startDir = moduleShapedStartDir, explicitRepoRoot = null)

        shouldThrow<IllegalArgumentException> {
            command.parse(arrayOf("--repos", "__wiring_test_only__"))
        }

        command.resolvedCorpusRoot shouldBe repoRoot.resolve(".benchmark-corpus")
    }
})
