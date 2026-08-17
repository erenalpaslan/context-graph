package io.contextgraph.core

data class ExtractionResult(
    val artifact: Artifact,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val diagnostics: List<ExtractionDiagnostic> = emptyList(),
    /**
     * Pass 1's other output, alongside [nodes]: identifier occurrences seen at a call
     * site, recorded for pass 2 (`io.contextgraph.ingest.ReferenceResolver`) to resolve
     * against the persistent symbol table. No extractor populates this yet -- it is the
     * seam a language's [ResourceExtractor] fills in once it walks call/invocation
     * expressions, not just declarations. Defaulting to empty keeps every existing
     * extractor's `ExtractionResult(artifact, nodes, edges)` call compiling unchanged.
     */
    val references: List<UnresolvedReference> = emptyList()
)
