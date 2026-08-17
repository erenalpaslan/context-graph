package io.contextgraph.treesitter

import java.io.File

/**
 * Loads a compiled tree-sitter grammar native library from classpath resources.
 *
 * Mirrors the resource layout the official `ktreesitter` JVM artifact itself uses:
 * `/lib/<os>/<arch>/lib<name>.<ext>`. The `:modules:tree-sitter` build compiles one such
 * library per grammar, for whichever platform the build runs on, into that layout under
 * `build/generated/tree-sitter-natives` (wired in as a resources source directory) --
 * see `build.gradle.kts`.
 *
 * Native libraries cannot be `dlopen`ed directly out of a jar, so the matching resource
 * is copied to a temp file before [System.load].
 */
internal object NativeGrammarLoader {
    private val loaded = mutableSetOf<String>()

    @Synchronized
    fun load(libName: String) {
        if (!loaded.add(libName)) return
        val osName = System.getProperty("os.name").lowercase()
        val archName = System.getProperty("os.arch").lowercase()
        val (os, ext) = when {
            "linux" in osName -> "linux" to "so"
            "mac" in osName -> "macos" to "dylib"
            else -> throw UnsupportedOperationException(
                "Unsupported operating system for tree-sitter natives: $osName"
            )
        }
        val arch = when {
            "aarch64" in archName || "arm64" in archName -> "aarch64"
            "amd64" in archName || "x86_64" in archName -> "x64"
            else -> throw UnsupportedOperationException(
                "Unsupported architecture for tree-sitter natives: $archName"
            )
        }
        val resourcePath = "/lib/$os/$arch/lib$libName.$ext"
        val resource = NativeGrammarLoader::class.java.getResource(resourcePath)
            ?: throw UnsatisfiedLinkError(
                "No native tree-sitter grammar library found at classpath resource '$resourcePath'. " +
                    "Was :modules:tree-sitter built on this platform (macOS arm64 / Linux x64 only)?"
            )
        val tempFile = File.createTempFile("lib$libName", ".$ext")
        tempFile.deleteOnExit()
        resource.openStream().use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        System.load(tempFile.absolutePath)
    }
}
