package io.contextgraph.treesitter

import io.contextgraph.core.NodeId

/**
 * Declaration-site node identity, shared by every [LanguageSupport.extract]
 * implementation: repo-relative path plus the tree-sitter scope chain.
 *
 * This is what makes identity per-declaration-site rather than per-name: two files
 * declaring a class with the same name always produce two different [repoRelativePath]
 * values, so [of] can never collide across files even though the scope chain (the part
 * after `#`) is identical. Nothing here merges declarations by name -- that is
 * deliberate, see the run's interview decisions on `EntityResolver`.
 *
 * Format: `Auth/UserService.java#UserService.save` -- stable across reindexes of
 * unchanged source because it derives only from the file's path and the declaration's
 * position in the syntax tree, never from anything generated (timestamps, DB row ids).
 */
object DeclarationSiteId {
    /** ID for the file node itself: just the repo-relative path, no scope chain. */
    fun file(repoRelativePath: String): NodeId = NodeId(repoRelativePath)

    /** ID for a declaration nested at [scopeChain] inside [repoRelativePath]. */
    fun of(repoRelativePath: String, scopeChain: List<String>): NodeId {
        require(scopeChain.isNotEmpty()) { "scopeChain must have at least one segment" }
        return NodeId("$repoRelativePath#${scopeChain.joinToString(".")}")
    }
}
