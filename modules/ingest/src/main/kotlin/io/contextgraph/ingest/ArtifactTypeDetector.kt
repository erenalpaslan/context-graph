package io.contextgraph.ingest

import io.contextgraph.core.NodeType
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

object ArtifactTypeDetector {
    private val codeExtensions = setOf("kt", "java", "py", "ts", "tsx", "js", "jsx", "go", "rs", "rb", "php", "cpp", "c", "h", "cs", "swift", "scala", "groovy")
    private val configNames = setOf("dockerfile", ".dockerignore", ".gitignore", ".editorconfig", ".prettierrc", ".eslintrc")
    private val configExtensions = setOf("yml", "yaml", "toml", "ini", "cfg", "conf", "json", "xml", "properties")
    private val packageFileNames = setOf("package.json", "pom.xml", "build.gradle", "build.gradle.kts", "cargo.toml", "requirements.txt", "go.mod", "go.sum", "pyproject.toml", "setup.py", "setup.cfg", "gemfile", "composer.json", "package-lock.json", "yarn.lock")

    fun detect(path: Path): NodeType {
        val name = path.name.lowercase()
        val ext = path.extension.lowercase()

        return when {
            name in packageFileNames -> NodeType.PackageFile
            name == "dockerfile" || name.startsWith("dockerfile.") -> NodeType.ConfigFile
            name in configNames -> NodeType.ConfigFile
            ext == "pdf" -> NodeType.PDF
            ext in setOf("sql", "ddl", "dml") -> NodeType.DatabaseSchema
            ext == "md" || ext == "mdx" -> NodeType.MarkdownFile
            ext in setOf("rst", "adoc", "txt") -> NodeType.Document
            ext in setOf("png", "jpg", "jpeg", "gif", "svg", "webp") -> NodeType.Image
            ext in setOf("drawio", "excalidraw", "mermaid") -> NodeType.Diagram
            isTestFile(name, ext) -> NodeType.TestFile
            ext in codeExtensions -> NodeType.CodeFile
            ext in configExtensions -> NodeType.ConfigFile
            else -> NodeType.Document
        }
    }

    private fun isTestFile(name: String, ext: String): Boolean {
        if (ext !in codeExtensions) return false
        return name.contains("test") || name.contains("spec") || name.startsWith("test_")
    }
}
