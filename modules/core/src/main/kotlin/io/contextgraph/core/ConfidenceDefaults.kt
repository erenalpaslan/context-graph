package io.contextgraph.core

object ConfidenceDefaults {
    const val AST_SYMBOL = 0.98
    const val IMPORT_RELATION = 0.98
    const val FUNCTION_CALL = 0.90
    const val SQL_FOREIGN_KEY = 0.99
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
