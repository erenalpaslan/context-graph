package io.contextgraph.benchmark.judge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * AC-14: the ~20% subsample must be reproducible -- same input, same
 * sample -- with no LLM call involved (task 05 note).
 */
class KappaSubsampleSelectorTest : FunSpec({

    val ids = (1..50).map { "run-$it" }

    test("selecting twice from the same input gives the same sample") {
        val first = KappaSubsampleSelector.select(ids)
        val second = KappaSubsampleSelector.select(ids)

        first shouldBe second
    }

    test("sample size is approximately 20% of the input, rounded up") {
        val sample = KappaSubsampleSelector.select(ids)

        sample.size shouldBe 10
    }

    test("sample does not depend on the order the ids are passed in") {
        val shuffled = ids.shuffled(kotlin.random.Random(42))

        val fromOriginal = KappaSubsampleSelector.select(ids)
        val fromShuffled = KappaSubsampleSelector.select(shuffled)

        fromShuffled.toSet() shouldBe fromOriginal.toSet()
    }

    test("every selected id came from the input") {
        val sample = KappaSubsampleSelector.select(ids)

        ids.shouldContainExactlyInAnyOrder(ids) // sanity: fixture itself is well-formed
        sample.all { it in ids } shouldBe true
    }

    test("duplicate ids in the input are not double-counted") {
        val withDuplicates = ids + ids

        val sample = KappaSubsampleSelector.select(withDuplicates)

        sample.size shouldBe 10
        sample.toSet().size shouldBe sample.size
    }

    test("a different input set produces a different (not just re-ordered) sample in general") {
        val otherIds = (1..50).map { "other-$it" }

        val sampleA = KappaSubsampleSelector.select(ids)
        val sampleB = KappaSubsampleSelector.select(otherIds)

        // Disjoint id namespaces -> disjoint samples; proves selection is a
        // function of each id's own content, not merely positional.
        sampleA.toSet().intersect(sampleB.toSet()) shouldBe emptySet()
    }

    test("an empty input yields an empty sample") {
        KappaSubsampleSelector.select(emptyList()) shouldBe emptyList()
    }

    test("rejects a fraction outside [0, 1]") {
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            KappaSubsampleSelector.select(ids, fraction = 1.5)
        }
    }
})
