package io.contextgraph.treesitter.grammars

import io.contextgraph.treesitter.LanguageSupport
import io.contextgraph.treesitter.NativeGrammarLoader
import io.contextgraph.treesitter.SymbolExtraction
import io.contextgraph.treesitter.SymbolExtractionRequest
import io.github.treesitter.ktreesitter.Language

/**
 * JNI binding to the native `libcontextgraph_ts_tsx.{so,dylib}` compiled from the `tsx/`
 * grammar inside the vendored, version-pinned `tree-sitter/tree-sitter-typescript` repo
 * (the same repo/commit as plain TypeScript -- see `TypeScriptGrammar.kt`). See
 * `JavaGrammar.kt` for how this binding is produced and why the fully-qualified name is
 * load-bearing.
 */
internal object TreeSitterTsx {
    init { NativeGrammarLoader.load("contextgraph_ts_tsx") }

    external fun language(): Long
}

/**
 * TSX language support: parses via the vendored `tree-sitter-tsx` grammar and extracts
 * declaration-site symbol nodes via [TsFamilySymbolExtractor] -- the walker shared with
 * [TypeScriptLanguageSupport] and [JavaScriptLanguageSupport].
 */
object TsxLanguageSupport : LanguageSupport {
    override val id: String = "tsx"
    override val extensions: Set<String> = setOf("tsx")
    override fun language(): Language = Language(TreeSitterTsx.language())

    override fun extract(request: SymbolExtractionRequest): SymbolExtraction =
        TsFamilySymbolExtractor(request).extract()
}
