package io.contextgraph.benchmark.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Review finding: an unrecognized model used to fall back to the `claude-sonnet-5` rate
 * silently. That is a wrong `costUsd` with no signal, feeding straight into AC-21's break-even.
 * It now throws instead.
 */
class ModelPricingTest : FunSpec({

    test("known agent-tier model produces a positive cost") {
        ModelPricing.costUsd("claude-sonnet-5", inputTokens = 1_000_000, outputTokens = 1_000_000) shouldBeGreaterThan 0.0
    }

    test("known judge-tier model produces a positive cost") {
        ModelPricing.costUsd("claude-opus-5", inputTokens = 1_000_000, outputTokens = 1_000_000) shouldBeGreaterThan 0.0
    }

    test("cost scales with the standard per-model rate, not a fallback rate") {
        val sonnetCost = ModelPricing.costUsd("claude-sonnet-5", inputTokens = 1_000_000, outputTokens = 1_000_000)
        val opusCost = ModelPricing.costUsd("claude-opus-5", inputTokens = 1_000_000, outputTokens = 1_000_000)
        sonnetCost shouldBe 18.0 // $3 in + $15 out per MTok
        opusCost shouldBe 30.0 // $5 in + $25 out per MTok
    }

    test("an unrecognized model throws rather than silently substituting another model's rate") {
        shouldThrow<IllegalArgumentException> {
            ModelPricing.costUsd("claude-typo-9000", inputTokens = 100, outputTokens = 100)
        }
    }

    // ------------------------------------------------------------- task 20: costUsdOrNull/hasRate

    test("costUsdOrNull matches costUsd for a known model") {
        ModelPricing.costUsdOrNull("claude-sonnet-5", inputTokens = 1_000_000, outputTokens = 1_000_000) shouldBe 18.0
    }

    test("costUsdOrNull returns null -- not 0.0, not a thrown exception -- for a model with no pricing entry (e.g. gpt-4.1-nano)") {
        ModelPricing.costUsdOrNull("gpt-4.1-nano", inputTokens = 1_000_000, outputTokens = 1_000_000).shouldBeNull()
    }

    test("hasRate reports which models costUsdOrNull can actually price") {
        ModelPricing.hasRate("claude-sonnet-5") shouldBe true
        ModelPricing.hasRate("gpt-4.1-nano") shouldBe false
    }
})
