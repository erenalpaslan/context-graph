package io.contextgraph.treesitter.grammars

import io.contextgraph.treesitter.LanguageSupport
import io.contextgraph.treesitter.NativeGrammarLoader
import io.contextgraph.treesitter.SymbolExtraction
import io.contextgraph.treesitter.SymbolExtractionRequest
import io.github.treesitter.ktreesitter.Language

/**
 * JNI binding to the native `libcontextgraph_ts_typescript.{so,dylib}` compiled from the
 * `typescript/` grammar inside the vendored, version-pinned `tree-sitter/tree-sitter-typescript`
 * repo. See `JavaGrammar.kt` for how this binding is produced and why the fully-qualified
 * name is load-bearing. The TSX dialect is a separate grammar -- see `TsxGrammar.kt`.
 */
internal object TreeSitterTypeScript {
    init { NativeGrammarLoader.load("contextgraph_ts_typescript") }

    external fun language(): Long
}

/**
 * TypeScript language support: parses via the vendored `tree-sitter-typescript` grammar
 * and extracts declaration-site symbol nodes via [TsFamilySymbolExtractor] -- the walker
 * shared with [TsxLanguageSupport] and [JavaScriptLanguageSupport].
 */
object TypeScriptLanguageSupport : LanguageSupport {
    override val id: String = "typescript"
    override val extensions: Set<String> = setOf("ts")
    override fun language(): Language = Language(TreeSitterTypeScript.language())

    override fun extract(request: SymbolExtractionRequest): SymbolExtraction =
        TsFamilySymbolExtractor(request).extract()
}
