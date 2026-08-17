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
    val goldFacts: List<GoldFact>
)
