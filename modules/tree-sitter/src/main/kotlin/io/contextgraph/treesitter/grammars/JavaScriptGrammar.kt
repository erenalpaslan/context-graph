package io.contextgraph.treesitter.grammars

import io.contextgraph.treesitter.LanguageSupport
import io.contextgraph.treesitter.NativeGrammarLoader
import io.contextgraph.treesitter.SymbolExtraction
import io.contextgraph.treesitter.SymbolExtractionRequest
import io.github.treesitter.ktreesitter.Language

/**
 * JNI binding to the native `libcontextgraph_ts_javascript.{so,dylib}` compiled from the
 * vendored, version-pinned `tree-sitter/tree-sitter-javascript` grammar. See
 * `JavaGrammar.kt` for how this binding is produced and why the fully-qualified name is
 * load-bearing.
 */
internal object TreeSitterJavaScript {
    init { NativeGrammarLoader.load("contextgraph_ts_javascript") }

    external fun language(): Long
}

/**
 * JavaScript language support: parses via the vendored `tree-sitter-javascript` grammar
 * (which natively understands JSX, so `.jsx` is claimed here rather than by the TSX
 * grammar) and extracts declaration-site symbol nodes via [TsFamilySymbolExtractor] --
 * the walker shared with [TypeScriptLanguageSupport] and [TsxLanguageSupport].
 */
object JavaScriptLanguageSupport : LanguageSupport {
    override val id: String = "javascript"
    override val extensions: Set<String> = setOf("js", "jsx")
    override fun language(): Language = Language(TreeSitterJavaScript.language())

    override fun extract(request: SymbolExtractionRequest): SymbolExtraction =
        TsFamilySymbolExtractor(request).extract()
}
