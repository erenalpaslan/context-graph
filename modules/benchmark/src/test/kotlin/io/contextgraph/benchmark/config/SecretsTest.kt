package io.contextgraph.benchmark.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A temp directory shaped like a repo root: it carries the marker `RepoRoot` walks up to find,
 * so `Secrets` resolves `local.properties` against it exactly as it would against the real one.
 */
private fun repoLike(): Path =
    Files.createTempDirectory("secrets-test").also {
        it.resolve("settings.gradle.kts").writeText("// marker\n")
    }

class SecretsTest : FunSpec({

    test("reads the value from local.properties when the environment does not set it") {
        val root = repoLike()
        root.resolve("local.properties").writeText("ANTHROPIC_API_KEY=sk-ant-from-file\n")

        Secrets.resolve("ANTHROPIC_API_KEY", env = { null }, startDir = root) shouldBe
            "sk-ant-from-file"
    }

    test("the environment wins over local.properties so CI can override a developer's file") {
        val root = repoLike()
        root.resolve("local.properties").writeText("ANTHROPIC_API_KEY=sk-ant-from-file\n")

        Secrets.resolve(
            "ANTHROPIC_API_KEY",
            env = { "sk-ant-from-env" },
            startDir = root
        ) shouldBe "sk-ant-from-env"
    }

    test("an exported-but-blank environment variable falls through to local.properties") {
        val root = repoLike()
        root.resolve("local.properties").writeText("ANTHROPIC_API_KEY=sk-ant-from-file\n")

        Secrets.resolve("ANTHROPIC_API_KEY", env = { "   " }, startDir = root) shouldBe
            "sk-ant-from-file"
    }

    test("a blank value in local.properties resolves to null rather than an empty secret") {
        val root = repoLike()
        root.resolve("local.properties").writeText("ANTHROPIC_API_KEY=\n")

        Secrets.resolve("ANTHROPIC_API_KEY", env = { null }, startDir = root).shouldBeNull()
    }

    test("surrounding whitespace in local.properties is trimmed off the value") {
        val root = repoLike()
        root.resolve("local.properties").writeText("ANTHROPIC_API_KEY=  sk-ant-padded  \n")

        Secrets.resolve("ANTHROPIC_API_KEY", env = { null }, startDir = root) shouldBe
            "sk-ant-padded"
    }

    test("a missing local.properties resolves to null instead of throwing") {
        val root = repoLike()

        Secrets.resolve("ANTHROPIC_API_KEY", env = { null }, startDir = root).shouldBeNull()
    }

    test("a start directory with no repo root above it resolves to null instead of throwing") {
        // No marker anywhere up the tree: RepoRoot.find would throw, and Secrets must not.
        // This is the case that would otherwise break the LLM-free retrieval axis, which
        // never needs a key but still constructs clients that look one up.
        val orphan = Files.createTempDirectory("secrets-test-orphan").resolve("nested")
        orphan.createDirectories()

        Secrets.resolve("ANTHROPIC_API_KEY", env = { null }, startDir = orphan).shouldBeNull()
    }

    test("an unreadable local.properties resolves to null instead of throwing") {
        val root = repoLike()
        // A directory where the file is expected: Properties.load cannot read it.
        root.resolve("local.properties").createDirectories()

        Secrets.resolve("ANTHROPIC_API_KEY", env = { null }, startDir = root).shouldBeNull()
    }

    test("task 20: OPENAI_API_KEY resolves through the same environment-first, then local.properties path as ANTHROPIC_API_KEY") {
        val root = repoLike()
        root.resolve("local.properties").writeText("OPENAI_API_KEY=sk-from-file\n")

        Secrets.resolve(Secrets.OPENAI_API_KEY, env = { null }, startDir = root) shouldBe "sk-from-file"
        Secrets.resolve(Secrets.OPENAI_API_KEY, env = { "sk-from-env" }, startDir = root) shouldBe "sk-from-env"
    }

    test("the missing-secret message names both places the value was looked for") {
        val message = Secrets.missingMessage("ANTHROPIC_API_KEY", Path.of("/repo/local.properties"))

        message.contains("environment variable") shouldBe true
        message.contains("/repo/local.properties") shouldBe true
        message.contains("ANTHROPIC_API_KEY") shouldBe true
    }
})
