package io.contextgraph.ingest

import io.contextgraph.core.ConfidenceDefaults
import io.contextgraph.core.EdgeType
import io.contextgraph.core.GraphNode
import io.contextgraph.core.NodeId
import io.contextgraph.core.StorageAdapter
import io.contextgraph.core.UnresolvedReference

/**
 * The four rungs [ResolutionLadder] resolves a reference through, in descending order of
 * precision. [confidenceFor] is the single source of truth mapping a rung -- plus whether
 * it matched more than one candidate -- to a [ConfidenceDefaults] value.
 */
enum class ResolutionRung(val label: String) {
    LOCAL_SCOPE("local_scope"),
    FILE_IMPORTS("file_imports"),
    SAME_DIRECTORY("same_directory"),
    REPO_UNIQUE_NAME("repo_unique_name");

    fun confidenceFor(ambiguous: Boolean): Double = when (this) {
        LOCAL_SCOPE -> if (ambiguous) {
            ConfidenceDefaults.CALL_RESOLUTION_LOCAL_SCOPE_AMBIGUOUS
        } else {
            ConfidenceDefaults.CALL_RESOLUTION_LOCAL_SCOPE
        }
        FILE_IMPORTS -> if (ambiguous) {
            ConfidenceDefaults.CALL_RESOLUTION_FILE_IMPORTS_AMBIGUOUS
        } else {
            ConfidenceDefaults.CALL_RESOLUTION_FILE_IMPORTS
        }
        SAME_DIRECTORY -> if (ambiguous) {
            ConfidenceDefaults.CALL_RESOLUTION_SAME_DIRECTORY_AMBIGUOUS
        } else {
            ConfidenceDefaults.CALL_RESOLUTION_SAME_DIRECTORY
        }
        REPO_UNIQUE_NAME -> if (ambiguous) {
            ConfidenceDefaults.CALL_RESOLUTION_REPO_UNIQUE_NAME_AMBIGUOUS
        } else {
            ConfidenceDefaults.CALL_RESOLUTION_REPO_UNIQUE_NAME
        }
    }
}

/**
 * Turns a flat "resolved or not" match into a graded ladder (slice 10, on top of slice 09's
 * symbol table and naive full-rebuild resolver). Given every same-name declaration a
 * reference could mean ([StorageAdapter.findNodesByLabel]'s result), [resolve] narrows to
 * the most precise rung that matches at least one of them, stopping there -- an over-cap
 * match at a high rung never falls through to a looser one, it is simply too ambiguous to
 * resolve at all (see [ConfidenceDefaults.CALL_RESOLUTION_CANDIDATE_CAP], applied by the
 * caller, not here).
 *
 * Every rung is decided from data pass 1 already persisted -- [UnresolvedReference]'s own
 * fields, declaration-site [NodeId]s (`repoRelativePath#scope.chain`, see
 * `io.contextgraph.treesitter.DeclarationSiteId`), and [EdgeType.Imports] edges -- using one
 * uniform string-based algorithm applied identically to every language. Deliberately no
 * per-language special-casing: a language whose import syntax does not line up with file
 * paths (Python relative imports, Swift protocol witnesses) will simply resolve worse at
 * rung 2, and that is the honest signal this ladder exists to carry, not a defect to patch
 * around here.
 *
 * - **Local scope** -- the candidate *is* the referring declaration (self-recursion), is
 *   nested inside it (a local/closure declaration), or shares its immediate enclosing type.
 * - **File imports** -- the candidate's declaring file's basename appears as a path/dotted
 *   segment of something the referring file imports.
 * - **Same directory** -- the candidate's declaring file sits in the same directory as the
 *   referring file (this includes the referring file itself).
 * - **Repo-wide unique name** -- whatever is left: every same-name declaration in the repo.
 */
object ResolutionLadder {

    fun resolve(
        reference: UnresolvedReference,
        candidates: List<GraphNode>,
        storage: StorageAdapter
    ): Pair<ResolutionRung, List<GraphNode>>? {
        if (candidates.isEmpty()) return null

        val referringId = reference.referringSymbolId
        val referringPath = filePathOf(referringId)
        val referringScope = scopeChainOf(referringId)

        val localScope = candidates.filter { isLocalScope(it, referringId, referringPath, referringScope) }
        if (localScope.isNotEmpty()) return ResolutionRung.LOCAL_SCOPE to localScope

        val importedTokens = importedTokensFor(reference, storage)
        val fileImports = candidates.filter { isFileImport(it, importedTokens) }
        if (fileImports.isNotEmpty()) return ResolutionRung.FILE_IMPORTS to fileImports

        val sameDirectory = candidates.filter { isSameDirectory(it, referringPath) }
        if (sameDirectory.isNotEmpty()) return ResolutionRung.SAME_DIRECTORY to sameDirectory

        return ResolutionRung.REPO_UNIQUE_NAME to candidates
    }

    private fun isLocalScope(
        candidate: GraphNode,
        referringId: NodeId,
        referringPath: String,
        referringScope: List<String>
    ): Boolean {
        if (candidate.id == referringId) return true // self-recursion: strongest possible signal

        if (filePathOf(candidate.id) != referringPath) return false
        if (referringScope.isEmpty()) return false // reference sits at file level: no function/type to share

        val candidateScope = scopeChainOf(candidate.id)
        if (candidateScope.isEmpty()) return false

        // Nested inside the referring declaration itself -- a local/closure declaration.
        if (candidateScope.size > referringScope.size &&
            candidateScope.subList(0, referringScope.size) == referringScope
        ) {
            return true
        }

        // Shares the immediate enclosing type with the referring declaration.
        val referringType = referringScope.dropLast(1)
        if (referringType.isEmpty()) return false
        return candidateScope.dropLast(1) == referringType
    }

    private fun importedTokensFor(reference: UnresolvedReference, storage: StorageAdapter): Set<String> {
        val fileId = NodeId(reference.repoRelativePath)
        return storage.getEdgesFrom(fileId)
            .filter { it.type == EdgeType.Imports }
            .mapNotNull { storage.getNode(it.target)?.label }
            .flatMap { it.split('.', '/', '\\') }
            .filterTo(mutableSetOf()) { it.isNotBlank() }
    }

    private fun isFileImport(candidate: GraphNode, importedTokens: Set<String>): Boolean {
        if (importedTokens.isEmpty()) return false
        val baseName = filePathOf(candidate.id).substringAfterLast('/').substringBeforeLast('.')
        return baseName.isNotBlank() && baseName in importedTokens
    }

    private fun isSameDirectory(candidate: GraphNode, referringPath: String): Boolean =
        directoryOf(filePathOf(candidate.id)) == directoryOf(referringPath)

    private fun filePathOf(nodeId: NodeId): String = nodeId.value.substringBefore('#')

    private fun scopeChainOf(nodeId: NodeId): List<String> {
        val idx = nodeId.value.indexOf('#')
        if (idx < 0) return emptyList()
        return nodeId.value.substring(idx + 1).split('.')
    }

    private fun directoryOf(path: String): String = path.substringBeforeLast('/', "")
}
