package io.contextgraph.core

object ConfidenceDefaults {
    const val AST_SYMBOL = 0.98
    const val IMPORT_RELATION = 0.98
    /** SiblingOf edges (slice 11): derived deterministically from an already-extracted
     *  `fqn` match, not a probabilistic resolution, so it sits with AST_SYMBOL/IMPORT_RELATION. */
    const val SIBLING_GROUPING = 0.98
    const val FUNCTION_CALL = 0.90
    const val SQL_FOREIGN_KEY = 0.99

    // --- Call-resolution ladder (slice 10) ---
    //
    // `io.contextgraph.ingest.ReferenceResolver` resolves each unresolved reference by
    // descending four rungs of precision -- local scope, then file imports, then same
    // directory, then repo-wide unique name -- stopping at the first rung that yields any
    // candidates (see `io.contextgraph.ingest.ResolutionLadder`). A rung that yields exactly
    // one candidate is unambiguous; one that yields two or three (see
    // CALL_RESOLUTION_CANDIDATE_CAP) is ambiguous and gets a reduced confidence. The eight
    // values below are a single strictly-decreasing sequence -- every unambiguous value
    // exceeds every ambiguous one -- so a `minConfidence` of 0.8 cleanly separates
    // unambiguously-resolved call edges from ambiguous ones (AC-15), and higher rungs always
    // carry strictly higher confidence than lower ones regardless of ambiguity (AC-13).
    const val CALL_RESOLUTION_LOCAL_SCOPE = 0.97
    const val CALL_RESOLUTION_FILE_IMPORTS = 0.93
    const val CALL_RESOLUTION_SAME_DIRECTORY = 0.87
    const val CALL_RESOLUTION_REPO_UNIQUE_NAME = 0.80
    const val CALL_RESOLUTION_LOCAL_SCOPE_AMBIGUOUS = 0.75
    const val CALL_RESOLUTION_FILE_IMPORTS_AMBIGUOUS = 0.65
    const val CALL_RESOLUTION_SAME_DIRECTORY_AMBIGUOUS = 0.55
    const val CALL_RESOLUTION_REPO_UNIQUE_NAME_AMBIGUOUS = 0.45

    /**
     * A precision guard, not an optimisation. A rung yielding more than this many
     * candidates emits no `Calls` edge at all rather than a hairball of guesses for the
     * common names enterprise codebases collide on most -- `save`, `execute`, `handle`,
     * `process`. Named so the threshold is never a buried literal in a condition.
     */
    const val CALL_RESOLUTION_CANDIDATE_CAP = 3
    const val MARKDOWN_HEADING = 0.95
    const val MARKDOWN_CONCEPT = 0.75
    const val PDF_SECTION = 0.80
    const val CONFIG_DEPENDENCY = 0.95
    const val LLM_CONCEPT_MIN = 0.65
    const val LLM_CONCEPT_MAX = 0.85
    const val LLM_RELATION_MIN = 0.55
    const val LLM_RELATION_MAX = 0.80
    const val EMBEDDING_SIMILARITY_MIN = 0.40
    const val VISION_RELATION_MIN = 0.45
    const val LOW_CONFIDENCE_THRESHOLD = 0.65
}
