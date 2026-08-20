package io.contextgraph.benchmark.judge

import io.contextgraph.benchmark.model.Evidence
import io.contextgraph.benchmark.model.FactScore
import io.contextgraph.benchmark.model.GoldFact
import io.contextgraph.benchmark.model.JudgeScore
import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.model.QuestionCategory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

/**
 * A second judge that agrees with the primary judge on every fact except
 * one -- deterministic and hand-worked, so the expected agreement rate and
 * kappa are known ahead of time rather than computed the same way the code
 * computes them.
 */
private class DisagreesOnOneFactClient : JudgeClient {
    override suspend fun scoreFacts(input: JudgeInput, judgeModel: String): List<FactScore> =
        input.goldFacts.mapIndexed { index, fact ->
            // Every fact is a "hit" from the primary judge (see the fixture
            // below); the secondary judge here flips exactly the first fact
            // of each question to a miss.
            FactScore(fact.id, hit = index != 0)
        }
}

private class AllHitClient : JudgeClient {
    override suspend fun scoreFacts(input: JudgeInput, judgeModel: String): List<FactScore> =
        input.goldFacts.map { FactScore(it.id, hit = true) }
}

class KappaValidatorTest : FunSpec({

    fun questionFor(id: String) = Question(
        id = id,
        repoId = "excalidraw",
        text = "Question $id",
        category = QuestionCategory.GRAPH_HEAVY,
        goldFacts = listOf(
            GoldFact("f1", "Fact one", Evidence("src/Foo.kt", 1)),
            GoldFact("f2", "Fact two", Evidence("src/Bar.kt", 2)),
            GoldFact("f3", "Fact three", Evidence("src/Baz.kt", 3))
        )
    )

    fun scoredRun(id: String): ScoredRun {
        val question = questionFor(id)
        val primaryScore = JudgeScore(
            id = "$id:claude-opus-5",
            runId = id,
            judgeModel = "claude-opus-5",
            factScores = listOf(FactScore("f1", true), FactScore("f2", true), FactScore("f3", true)),
            accuracyScore = 1.0
        )
        return ScoredRun(runId = id, question = question, answerText = "answer for $id", primaryScore = primaryScore)
    }

    val scoredRuns = (1..20).map { scoredRun("run-$it") }

    test("re-scores a reproducible ~20% subsample with a second judge model") {
        val validator = KappaValidator(JudgeScorer(DisagreesOnOneFactClient()))

        val result = runBlocking {
            validator.validate(scoredRuns, secondaryJudgeModel = "second-model")
        }

        result.totalRuns shouldBe 20
        result.sampledRunIds shouldHaveSize 4 // ceil(20 * 0.2)
        result.secondaryScores shouldHaveSize 4
        result.secondaryScores.all { it.judgeModel == "second-model" } shouldBe true
    }

    test("selecting the same runs twice gives the same subsample (reproducible per AC-14)") {
        val validator = KappaValidator(JudgeScorer(DisagreesOnOneFactClient()))

        val first = runBlocking { validator.validate(scoredRuns, secondaryJudgeModel = "second-model") }
        val second = runBlocking { validator.validate(scoredRuns, secondaryJudgeModel = "second-model") }

        first.sampledRunIds shouldBe second.sampledRunIds
    }

    test("reports agreement rate and Cohen's kappa computed from the two judges' fact-level verdicts") {
        val validator = KappaValidator(JudgeScorer(DisagreesOnOneFactClient()))

        val result = runBlocking {
            validator.validate(scoredRuns, secondaryJudgeModel = "second-model")
        }

        // Every sampled run: primary says hit/hit/hit, secondary says
        // miss/hit/hit -- 2 of 3 facts agree, on every sampled run.
        result.agreementRate shouldBe (2.0 / 3.0 plusOrMinus 1e-9)
        // Secondary judge's marginal hit rate is 2/3, primary's is 1.0 (all
        // hits): pe = 1.0 * (2.0/3.0) + 0.0 * (1.0/3.0) = 2/3, same as po,
        // so kappa is 0 despite 2/3 raw agreement -- the whole point of the
        // chance-corrected statistic over the raw percentage.
        result.kappa shouldBe (0.0 plusOrMinus 1e-9)
    }

    test("records which model was the primary and which was the secondary judge") {
        val validator = KappaValidator(JudgeScorer(DisagreesOnOneFactClient()))

        val result = runBlocking {
            validator.validate(scoredRuns, secondaryJudgeModel = "second-model")
        }

        result.primaryJudgeModel shouldBe "claude-opus-5"
        result.secondaryJudgeModel shouldBe "second-model"
    }

    /**
     * A primary [JudgeScore] that (for whatever reason -- persisted from an
     * earlier run, constructed by a caller outside this module) doesn't
     * cover the full gold-fact set must not have its missing fact silently
     * dropped from the kappa computation. Dropping it would shrink `n`
     * (here: 3 facts collapse to 2) and, in this fixture, inflate perfect
     * agreement out of what should be a disagreement -- the same failure
     * mode [JudgeScorer] was fixed to prevent, but at the pairing seam
     * instead of the scoring seam.
     */
    test("a primary score missing a gold fact id is reconciled as a miss, not dropped from the pairing") {
        val question = questionFor("run-x")
        val incompletePrimary = JudgeScore(
            id = "run-x:claude-opus-5",
            runId = "run-x",
            judgeModel = "claude-opus-5",
            // f2 is missing entirely -- not scored as a miss, just absent.
            factScores = listOf(FactScore("f1", true), FactScore("f3", true)),
            accuracyScore = 1.0
        )
        val run = ScoredRun(runId = "run-x", question = question, answerText = "answer", primaryScore = incompletePrimary)
        val validator = KappaValidator(JudgeScorer(AllHitClient()))

        val result = runBlocking { validator.validate(listOf(run), secondaryJudgeModel = "second-model", fraction = 1.0) }

        // f1: true/true agree. f2: primary absent -> reconciled as false; secondary true -> disagree.
        // f3: true/true agree. 2 of 3 facts agree -- NOT 2 of 2 (which a dropped f2 would give: po = 1.0).
        result.agreementRate shouldBe (2.0 / 3.0 plusOrMinus 1e-9)
        // aHitRate = 2/3 (f1, f3), bHitRate = 1.0 (all hits) -> pe = 2/3 = po -> kappa = 0,
        // not the kappa = 1.0 a 2-of-2 perfect-agreement pairing would produce.
        result.kappa shouldBe (0.0 plusOrMinus 1e-9)
    }
})
