package io.contextgraph.benchmark.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import io.contextgraph.benchmark.model.BenchmarkRun
import io.contextgraph.benchmark.model.Profile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries

class BenchmarkCliTest : FunSpec({

    test("--profile smoke is accepted and produces a valid, schema-versioned result JSON") {
        val dir = createTempDirectory("benchmark-cli-smoke")
        BenchmarkCli().parse(arrayOf("--profile", "smoke", "--output-dir", dir.toString()))

        val written = dir.listDirectoryEntries("*.json")
        written.size shouldBe 1

        val run = BenchmarkRun.readFrom(written.single())
        run.profile shouldBe Profile.SMOKE
        run.schemaVersion shouldBe BenchmarkRun.SCHEMA_VERSION
        run.questions.isEmpty() shouldBe true
    }

    test("--profile full is accepted") {
        val dir = createTempDirectory("benchmark-cli-full")
        BenchmarkCli().parse(arrayOf("--profile", "full", "--output-dir", dir.toString()))

        val run = BenchmarkRun.readFrom(dir.listDirectoryEntries("*.json").single())
        run.profile shouldBe Profile.FULL
    }

    test("an unrecognized profile is rejected with a non-zero-exit-coded, clear error") {
        val dir = createTempDirectory("benchmark-cli-invalid")
        shouldThrow<CliktError> {
            BenchmarkCli().parse(arrayOf("--profile", "bogus", "--output-dir", dir.toString()))
        }
        dir.listDirectoryEntries("*.json").isEmpty() shouldBe true
    }

    test("a missing --profile is rejected") {
        val dir = createTempDirectory("benchmark-cli-missing")
        shouldThrow<CliktError> {
            BenchmarkCli().parse(arrayOf("--output-dir", dir.toString()))
        }
    }
})
