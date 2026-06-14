package io.contextgraph.core

data class ExtractionDiagnostic(
    val severity: Severity,
    val message: String,
    val cause: Throwable? = null
) {
    enum class Severity { INFO, WARNING, ERROR }
}
