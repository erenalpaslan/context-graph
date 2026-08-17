package io.contextgraph.core

/**
 * Pass 1's other output, alongside declaration [GraphNode]s: an identifier occurrence
 * (`referenceName`) seen at a call site, recorded with enough context for pass 2 to
 * resolve it against the symbol table without re-reading the source file.
 *
 * [referringSymbolId] is the nearest enclosing declaration's [NodeId] (or the file's own
 * [NodeId] when the reference occurs outside any declaration) -- this is who the eventual
 * `Calls` edge, if any, will originate from. [repoRelativePath] and [artifactId] identify
 * which file/artifact produced this reference, so a later reindex of that same file can
 * find and replace exactly its own rows without touching any other file's.
 *
 * Deliberately thin: no resolution state, no candidate list, no confidence. Pass 2 (see
 * `io.contextgraph.ingest.ReferenceResolver`) is the only reader, and grading what it
 * finds is slice 10's job, not this type's.
 */
data class UnresolvedReference(
    val referenceName: String,
    val referringSymbolId: NodeId,
    val repoRelativePath: String,
    val artifactId: ArtifactId,
    val line: Int
)
