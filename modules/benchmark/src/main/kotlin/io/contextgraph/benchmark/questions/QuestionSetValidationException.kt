package io.contextgraph.benchmark.questions

/**
 * Thrown by [QuestionSetLoader] when a question-set data file fails
 * validation. Collects every problem found in one pass rather than stopping
 * at the first error, so a single load attempt reports everything that needs
 * fixing. Each entry in [errors] names the offending question and/or gold
 * fact id (AC-4 requires this for evidence problems specifically).
 */
class QuestionSetValidationException(
    val errors: List<String>,
    val source: String
) : RuntimeException(
    "Question set '$source' failed validation with ${errors.size} error(s):\n" +
        errors.joinToString("\n") { "  - $it" }
)
