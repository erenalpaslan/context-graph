package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.Question

/**
 * AC-23: the expected file set for a retrieval question is *derived* from its gold facts'
 * evidence, never written by hand as a second, separate ground truth. Every [Question.goldFacts]
 * entry already carries a mandatory `file:line` [io.contextgraph.benchmark.model.Evidence]
 * (AC-4, enforced at load time by `QuestionSetLoader`) -- this is nothing more than projecting
 * that field out and de-duplicating it. There is deliberately no other code path in this module
 * that constructs an expected-file set: if a caller needs one, it calls this.
 */
object ExpectedFileSet {
    fun of(question: Question): Set<String> = question.goldFacts.map { it.evidence.file }.toSet()
}
