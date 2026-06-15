package io.contextgraph.ingest

import io.contextgraph.core.NodeType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class ArtifactTypeDetectorTest : FunSpec({

    fun path(name: String) = Path.of(name)

    context("source code files") {
        test("Kotlin file is CodeFile") {
            ArtifactTypeDetector.detect(path("UserService.kt")) shouldBe NodeType.CodeFile
        }
        test("Java file is CodeFile") {
            ArtifactTypeDetector.detect(path("Main.java")) shouldBe NodeType.CodeFile
        }
        test("Python file is CodeFile") {
            ArtifactTypeDetector.detect(path("app.py")) shouldBe NodeType.CodeFile
        }
        test("TypeScript file is CodeFile") {
            ArtifactTypeDetector.detect(path("index.ts")) shouldBe NodeType.CodeFile
        }
        test("TSX file is CodeFile") {
            ArtifactTypeDetector.detect(path("Button.tsx")) shouldBe NodeType.CodeFile
        }
        test("Go file is CodeFile") {
            ArtifactTypeDetector.detect(path("main.go")) shouldBe NodeType.CodeFile
        }
    }

    context("test files") {
        test("file with 'test' in name is TestFile") {
            ArtifactTypeDetector.detect(path("UserServiceTest.kt")) shouldBe NodeType.TestFile
        }
        test("file starting with test_ is TestFile") {
            ArtifactTypeDetector.detect(path("test_utils.py")) shouldBe NodeType.TestFile
        }
        test("spec file is TestFile") {
            ArtifactTypeDetector.detect(path("auth.spec.ts")) shouldBe NodeType.TestFile
        }
    }

    context("documentation files") {
        test("Markdown file is MarkdownFile") {
            ArtifactTypeDetector.detect(path("README.md")) shouldBe NodeType.MarkdownFile
        }
        test("MDX file is MarkdownFile") {
            ArtifactTypeDetector.detect(path("intro.mdx")) shouldBe NodeType.MarkdownFile
        }
        test("RST file is Document") {
            ArtifactTypeDetector.detect(path("guide.rst")) shouldBe NodeType.Document
        }
        test("PDF file is PDF") {
            ArtifactTypeDetector.detect(path("paper.pdf")) shouldBe NodeType.PDF
        }
    }

    context("database files") {
        test("SQL file is DatabaseSchema") {
            ArtifactTypeDetector.detect(path("schema.sql")) shouldBe NodeType.DatabaseSchema
        }
        test("DDL file is DatabaseSchema") {
            ArtifactTypeDetector.detect(path("migrations.ddl")) shouldBe NodeType.DatabaseSchema
        }
    }

    context("config files") {
        test("YAML file is ConfigFile") {
            ArtifactTypeDetector.detect(path("config.yml")) shouldBe NodeType.ConfigFile
        }
        test("JSON file is ConfigFile") {
            ArtifactTypeDetector.detect(path("settings.json")) shouldBe NodeType.ConfigFile
        }
        test("TOML file is ConfigFile") {
            ArtifactTypeDetector.detect(path("app.toml")) shouldBe NodeType.ConfigFile
        }
        test("Dockerfile is ConfigFile") {
            ArtifactTypeDetector.detect(path("Dockerfile")) shouldBe NodeType.ConfigFile
        }
        test(".gitignore is ConfigFile") {
            ArtifactTypeDetector.detect(path(".gitignore")) shouldBe NodeType.ConfigFile
        }
    }

    context("package files") {
        test("package.json is PackageFile") {
            ArtifactTypeDetector.detect(path("package.json")) shouldBe NodeType.PackageFile
        }
        test("build.gradle.kts is PackageFile") {
            ArtifactTypeDetector.detect(path("build.gradle.kts")) shouldBe NodeType.PackageFile
        }
        test("pom.xml is PackageFile") {
            ArtifactTypeDetector.detect(path("pom.xml")) shouldBe NodeType.PackageFile
        }
        test("requirements.txt is PackageFile") {
            ArtifactTypeDetector.detect(path("requirements.txt")) shouldBe NodeType.PackageFile
        }
    }

    context("image and diagram files") {
        test("PNG file is Image") {
            ArtifactTypeDetector.detect(path("logo.png")) shouldBe NodeType.Image
        }
        test("SVG file is Image") {
            ArtifactTypeDetector.detect(path("icon.svg")) shouldBe NodeType.Image
        }
        test("drawio file is Diagram") {
            ArtifactTypeDetector.detect(path("arch.drawio")) shouldBe NodeType.Diagram
        }
    }

    context("unknown extensions") {
        test("unknown extension falls back to Document") {
            ArtifactTypeDetector.detect(path("data.xyz")) shouldBe NodeType.Document
        }
    }
})
