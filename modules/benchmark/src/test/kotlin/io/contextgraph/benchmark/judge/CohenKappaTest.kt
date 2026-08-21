package io.contextgraph.benchmark.judge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Cohen's kappa checked against known worked values -- no LLM call involved
 * (AC-14 / task 05 note: "Kappa hesabının kendisi saf bir fonksiyon").
 */
class CohenKappaTest : FunSpec({

    test("matches the textbook worked example: 50 items, kappa = 0.4") {
        // Classic two-rater yes/no confusion matrix (Cohen's kappa worked
        // example): 20 yes-yes, 5 A-yes/B-no, 10 A-no/B-yes, 15 no-no.
        val pairs = buildList {
            repeat(20) { add(true to true) }
            repeat(5) { add(true to false) }
            repeat(10) { add(false to true) }
            repeat(15) { add(false to false) }
        }

        val result = CohenKappa.compute(pairs)

        result.n shouldBe 50
        result.agreementRate shouldBe (0.70 plusOrMinus 1e-9)
        result.kappa shouldBe (0.4 plusOrMinus 1e-9)
    }

    test("perfect but non-trivial agreement gives kappa = 1.0") {
        val pairs = buildList {
            repeat(5) { add(true to true) }
            repeat(5) { add(false to false) }
        }

        val result = CohenKappa.compute(pairs)

        result.agreementRate shouldBe (1.0 plusOrMinus 1e-9)
        result.kappa shouldBe (1.0 plusOrMinus 1e-9)
    }

    test("agreement exactly at chance level gives kappa = 0.0") {
        val pairs = listOf(
            true to true,
            true to false,
            false to true,
            false to false
        )

        val result = CohenKappa.compute(pairs)

        result.kappa shouldBe (0.0 plusOrMinus 1e-9)
    }

    test("both raters uniformly agreeing on a single category is defined as kappa = 1.0") {
        val pairs = List(10) { true to true }

        val result = CohenKappa.compute(pairs)

        result.agreementRate shouldBe (1.0 plusOrMinus 1e-9)
        result.kappa shouldBe (1.0 plusOrMinus 1e-9)
    }

    test("rejects an empty input") {
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            CohenKappa.compute(emptyList())
        }
    }
})
