package io.contextgraph.core

import java.nio.file.Path

data class ExtractionContext(
    val projectRoot: Path,
    val config: ContextGraphConfig,
    val httpClient: Any? = null
)
