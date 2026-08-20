package io.contextgraph.benchmark.proxy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText

/**
 * [LiteLlmConfigGenerator.generate] has no parameter an API key value could travel in on -- the
 * same structural guarantee [io.contextgraph.benchmark.judge.buildJudgeMessages] uses for AC-11.
 * These tests prove the generated YAML routes each judge model to OpenAI under its own name
 * (task 17: "Judge modeli ... konfigürasyondan gelir, kodda sabitleme"; task 20: the provider
 * itself moved to OpenAI after the account's Anthropic key came back invalid) and never embeds a
 * secret value.
 */
class LiteLlmConfigGeneratorTest : FunSpec({
    test("routes a judge model to OpenAI under the identical model name, via litellm's env-var indirection") {
        val yaml = LiteLlmConfigGenerator.generate(listOf("gpt-4.1-nano"))

        yaml shouldContain "model_name: gpt-4.1-nano"
        yaml shouldContain "model: openai/gpt-4.1-nano"
        yaml shouldContain "api_key: os.environ/OPENAI_API_KEY"
    }

    test("routes every distinct model given, e.g. primary judge plus a kappa-validation secondary judge") {
        val yaml = LiteLlmConfigGenerator.generate(listOf("gpt-4.1-nano", "gpt-4.1-mini"))

        yaml shouldContain "model_name: gpt-4.1-nano"
        yaml shouldContain "model: openai/gpt-4.1-nano"
        yaml shouldContain "model_name: gpt-4.1-mini"
        yaml shouldContain "model: openai/gpt-4.1-mini"
    }

    test("the same model given twice produces one entry, not two") {
        val yaml = LiteLlmConfigGenerator.generate(listOf("gpt-4.1-nano", "gpt-4.1-nano"))

        yaml.split("model_name: gpt-4.1-nano").size - 1 shouldBe 1
    }

    test("at least one model is required") {
        shouldThrow<IllegalArgumentException> { LiteLlmConfigGenerator.generate(emptyList()) }
    }

    test("writeTo writes the generated text to disk, creating parent directories") {
        val dir = createTempDirectory("litellm-config-test")
        val path = dir.resolve("nested/config.yaml")

        LiteLlmConfigGenerator.writeTo(path, listOf("gpt-4.1-nano"))

        path.readText() shouldBe LiteLlmConfigGenerator.generate(listOf("gpt-4.1-nano"))
    }

    test("no literal key value ever appears -- only the env-var indirection, since generate() takes no key parameter at all") {
        val yaml = LiteLlmConfigGenerator.generate(listOf("gpt-4.1-nano"))
        yaml shouldNotContain "sk-"
    }
})
