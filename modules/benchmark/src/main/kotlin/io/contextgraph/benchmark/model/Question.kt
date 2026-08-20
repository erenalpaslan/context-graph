package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * A benchmark question, together with the gold facts a correct answer must
 * cover. This type only carries shape. Loading question sets from data
 * files, validating gold-fact count (3-6), rejecting duplicate question ids,
 * and auditing category distribution against AC-5's targets all belong to
 * slice 03 (its data files, its validator) — deliberately not enforced here.
 */
@Serializable
data class Question(
    val id: String,
    val repoId: String,
    val text: String,
    val category: QuestionCategory,
    val goldFacts: List<GoldFact>,
    /**
     * For set-valued questions: the complete, mechanically derived answer set (each element a
     * `path:line` call site). Null for the hand-written key-fact questions.
     *
     * Two things change when this is present. Scoring becomes exact -- precision, recall and F1
     * computed by set comparison, with no model in the loop -- so judge variance leaves the
     * measurement entirely. And the question can finally be one that reading does not already
     * answer: these sets come from a compiler's own resolver, and on this corpus a text search
     * scores a median F1 of 0.62 against them, dropping to 0.07 where the method name is shared
     * across many types. The previous gold set was built the other way round, by an agent citing
     * `path:line` for each fact it read, which guaranteed a reader could reach every one of them.
     */
    val expectedSet: List<String>? = null
)
