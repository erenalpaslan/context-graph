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
    val line: Int,
    /**
     * The simple name of the receiver's declared type (`session` in `session.getId()` declared
     * as `UserSessionModel` gives `"UserSessionModel"`), or null when pass 1 could not read one
     * off a declaration in scope.
     *
     * This is the one piece of type information name-based resolution cannot do without. `getId`
     * is declared on 1001 types in Keycloak; every one of them is an equally good name match, so
     * the candidate count clears the ambiguity cap and the call resolves to nothing at all. The
     * receiver's declared type reduces that set to one, and it is cheap to obtain: parameters,
     * fields and local variables all carry an explicit type at their declaration site, in the
     * same file, which pass 1 is already walking.
     *
     * A *hint*, not a fact: it is the receiver's static type, so a call to a method the declared
     * type inherits rather than declares will not match any candidate under that name. Pass 2
     * therefore falls back to the unfiltered candidate set when filtering by this empties it --
     * the hint can only ever narrow, never lose, a resolution that would otherwise have happened.
     */
    val receiverType: String? = null,
    /**
     * When the receiver is itself a call, that call's method name (`getParentSession` for
     * `authSession.getParentSession().getId()`), otherwise null.
     *
     * The half of chained access pass 1 can record and pass 2 cannot reconstruct: by pass 2 the
     * syntax is gone. Pass 2 supplies the other half, looking this name up in the symbol table
     * and reading the declared return type off the method it finds -- which is why this is a
     * name rather than a type. The method is usually declared in a different file, so the file
     * being walked has no way to know what it returns.
     *
     * This shape is not a corner: of the `getId` call sites still unresolved after
     * [receiverType] alone, the largest group by far carried no receiver hint precisely because
     * the receiver was a call.
     */
    val receiverCall: String? = null
)
