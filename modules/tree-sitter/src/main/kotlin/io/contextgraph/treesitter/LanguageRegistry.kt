package io.contextgraph.treesitter

import io.contextgraph.treesitter.grammars.JavaLanguageSupport
import io.contextgraph.treesitter.grammars.JavaScriptLanguageSupport
import io.contextgraph.treesitter.grammars.KotlinLanguageSupport
import io.contextgraph.treesitter.grammars.ObjectiveCLanguageSupport
import io.contextgraph.treesitter.grammars.PythonLanguageSupport
import io.contextgraph.treesitter.grammars.SwiftLanguageSupport
import io.contextgraph.treesitter.grammars.TsxLanguageSupport
import io.contextgraph.treesitter.grammars.TypeScriptLanguageSupport

/**
 * Resolves a file to the [LanguageSupport] that parses it.
 *
 * All eight grammar entries (seven languages; TypeScript and TSX are two grammars) are
 * pre-registered here so slices adding real extraction for one language never need to
 * touch this file -- see the per-language files under `grammars/` for what each one adds.
 * Registering a *ninth* language means implementing [LanguageSupport] in one new file and
 * adding one line to [all] below.
 */
object LanguageRegistry {
    val all: List<LanguageSupport> = listOf(
        JavaLanguageSupport,
        PythonLanguageSupport,
        JavaScriptLanguageSupport,
        TypeScriptLanguageSupport,
        TsxLanguageSupport,
        KotlinLanguageSupport,
        SwiftLanguageSupport,
        ObjectiveCLanguageSupport,
    )

    private val byExtension: Map<String, LanguageSupport> = buildMap {
        for (support in all) {
            for (ext in support.extensions) {
                val existing = this[ext]
                check(existing == null) {
                    "Extension '.$ext' is claimed by both '${existing?.id}' and '${support.id}'"
                }
                put(ext, support)
            }
        }
    }

    /**
     * Resolves [fileName] (a plain name or a path) to its [LanguageSupport], or `null` if
     * no grammar covers its extension. Never throws on an unknown extension.
     */
    fun resolve(fileName: String): LanguageSupport? {
        val ext = fileName.substringAfterLast('.', missingDelimiterValue = "")
        if (ext.isEmpty()) return null
        return byExtension[ext.lowercase()]
    }
}
