package io.contextgraph.ingest

import io.contextgraph.core.ContextGraphConfig
import io.contextgraph.core.ModuleRootConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class ModuleDetectorTest : FunSpec({

    fun tempRepo(): java.nio.file.Path = Files.createTempDirectory("module-detector-test")

    test("detects flat Gradle includes with no configuration") {
        val root = tempRepo()
        root.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "demo"
            include(
                ":app",
                ":core"
            )
            """.trimIndent()
        )

        val tree = ModuleDetector().detect(root, ContextGraphConfig())

        tree.modules.map { it.path } shouldContainExactlyInAnyOrder listOf("app", "core")
        tree.modules.map { it.name } shouldContainExactlyInAnyOrder listOf("app", "core")
        tree.modules.forEach { it.buildSystem shouldBe "gradle" }
        tree.modules.forEach { it.parentId shouldBe null }
    }

    test("nested Gradle modules are separate nodes forming a tree, not absorbed into the parent") {
        val root = tempRepo()
        root.resolve("settings.gradle.kts").writeText(
            """
            include(":app", ":app:feature-a", ":core")
            """.trimIndent()
        )

        val tree = ModuleDetector().detect(root, ContextGraphConfig())

        tree.modules.map { it.path } shouldContainExactlyInAnyOrder listOf("app", "app/feature-a", "core")
        val app = tree.modules.first { it.path == "app" }
        val featureA = tree.modules.first { it.path == "app/feature-a" }
        val core = tree.modules.first { it.path == "core" }

        featureA.parentId shouldBe app.id
        app.parentId shouldBe null
        core.parentId shouldBe null
        tree.children(app) shouldBe listOf(featureA)
    }

    test("detects package.json workspaces with no configuration") {
        val root = tempRepo()
        root.resolve("package.json").writeText(
            """{"name": "demo", "private": true, "workspaces": ["packages/*"]}"""
        )
        root.resolve("packages/foo").createDirectories()
        root.resolve("packages/foo/package.json").writeText("""{"name": "foo"}""")
        root.resolve("packages/bar").createDirectories()
        root.resolve("packages/bar/package.json").writeText("""{"name": "bar"}""")
        // Not a workspace member: no package.json inside, glob should skip it.
        root.resolve("packages/not-a-package").createDirectories()

        val tree = ModuleDetector().detect(root, ContextGraphConfig())

        tree.modules.map { it.path } shouldContainExactlyInAnyOrder listOf("packages/foo", "packages/bar")
        tree.modules.forEach { it.buildSystem shouldBe "npm" }
    }

    test("detects package.json workspaces declared as {packages: [...]}") {
        val root = tempRepo()
        root.resolve("package.json").writeText(
            """{"name": "demo", "workspaces": {"packages": ["apps/*"]}}"""
        )
        root.resolve("apps/web").createDirectories()
        root.resolve("apps/web/package.json").writeText("""{"name": "web"}""")

        val tree = ModuleDetector().detect(root, ContextGraphConfig())

        tree.modules.map { it.path } shouldContainExactlyInAnyOrder listOf("apps/web")
    }

    test("detects a root pyproject.toml with PEP 621 [project] name, no configuration") {
        val root = tempRepo()
        root.resolve("pyproject.toml").writeText(
            """
            [project]
            name = "demo-py"
            version = "0.1.0"
            """.trimIndent()
        )

        val tree = ModuleDetector().detect(root, ContextGraphConfig())

        tree.modules.map { it.path } shouldContainExactlyInAnyOrder listOf("")
        tree.modules.single().name shouldBe "demo-py"
        tree.modules.single().buildSystem shouldBe "python"
    }

    test("detects nested pyproject.toml packages declared with [tool.poetry] name") {
        val root = tempRepo()
        root.resolve("libs/foo").createDirectories()
        root.resolve("libs/foo/pyproject.toml").writeText(
            """
            [tool.poetry]
            name = "foo"
            version = "0.1.0"
            """.trimIndent()
        )
        root.resolve("libs/bar").createDirectories()
        root.resolve("libs/bar/pyproject.toml").writeText(
            """
            [tool.poetry]
            name = "bar"
            version = "0.1.0"
            """.trimIndent()
        )

        val tree = ModuleDetector().detect(root, ContextGraphConfig())

        tree.modules.map { it.path } shouldContainExactlyInAnyOrder listOf("libs/foo", "libs/bar")
        tree.modules.map { it.name } shouldContainExactlyInAnyOrder listOf("foo", "bar")
        tree.modules.forEach { it.buildSystem shouldBe "python" }
    }

    test("Groovy-style include with no parens is detected") {
        val root = tempRepo()
        root.resolve("settings.gradle").writeText(
            """
            rootProject.name = 'demo'
            include ':app', ':core'
            """.trimIndent()
        )

        val tree = ModuleDetector().detect(root, ContextGraphConfig())

        tree.modules.map { it.path } shouldContainExactlyInAnyOrder listOf("app", "core")
    }

    test("config-declared module roots override detection for matching paths and add new ones") {
        val root = tempRepo()
        root.resolve("settings.gradle.kts").writeText(
            """include(":app", ":core")"""
        )

        val config = ContextGraphConfig(
            moduleRoots = listOf(
                ModuleRootConfig(path = "app", name = "app-overridden"),
                ModuleRootConfig(path = "ios/MyApp")
            )
        )

        val tree = ModuleDetector().detect(root, config)

        tree.modules.map { it.path } shouldContainExactlyInAnyOrder listOf("app", "core", "ios/MyApp")
        val app = tree.modules.first { it.path == "app" }
        app.name shouldBe "app-overridden"
        app.buildSystem shouldBe "config"
        val core = tree.modules.first { it.path == "core" }
        core.buildSystem shouldBe "gradle"
        val ios = tree.modules.first { it.path == "ios/MyApp" }
        ios.name shouldBe "MyApp"
        ios.buildSystem shouldBe "config"
    }

    test("ownerOf attributes files to the most specific owning module, and files outside every module have no owner") {
        val root = tempRepo()
        root.resolve("settings.gradle.kts").writeText(
            """include(":app", ":app:feature-a")"""
        )

        val tree = ModuleDetector().detect(root, ContextGraphConfig())

        tree.ownerOf("app/feature-a/Foo.kt")?.path shouldBe "app/feature-a"
        tree.ownerOf("app/Bar.kt")?.path shouldBe "app"
        tree.ownerOf("README.md") shouldBe null
        tree.ownerOf("other/Unrelated.kt") shouldBe null

        val grouped = tree.filesByModule(
            listOf("app/feature-a/Foo.kt", "app/Bar.kt", "README.md", "other/Unrelated.kt")
        )
        val app = tree.modules.first { it.path == "app" }
        val featureA = tree.modules.first { it.path == "app/feature-a" }
        grouped[featureA] shouldContainExactly listOf("app/feature-a/Foo.kt")
        grouped[app] shouldContainExactly listOf("app/Bar.kt")
        grouped.values.flatten() shouldContainExactlyInAnyOrder listOf("app/feature-a/Foo.kt", "app/Bar.kt")
    }

    test("a repo with no recognisable build file and no config produces zero modules and no failure") {
        val root = tempRepo()
        root.resolve("src").createDirectories()
        root.resolve("src/Main.kt").writeText("fun main() {}")

        val tree = ModuleDetector().detect(root, ContextGraphConfig())

        tree.modules.shouldBeEmpty()
    }

    test("detection is deterministic across repeated runs on the same repo") {
        val root = tempRepo()
        root.resolve("settings.gradle.kts").writeText(
            """include(":app", ":app:feature-a", ":core")"""
        )
        root.resolve("package.json").writeText(
            """{"name": "demo", "workspaces": ["packages/*"]}"""
        )
        root.resolve("packages/foo").createDirectories()
        root.resolve("packages/foo/package.json").writeText("""{"name": "foo"}""")
        root.resolve("packages/bar").createDirectories()
        root.resolve("packages/bar/package.json").writeText("""{"name": "bar"}""")

        val first = ModuleDetector().detect(root, ContextGraphConfig())
        val second = ModuleDetector().detect(root, ContextGraphConfig())

        first.modules.map { it.id } shouldBe second.modules.map { it.id }
        first.modules.map { it.path } shouldBe second.modules.map { it.path }
        first.modules.map { it.parentId } shouldBe second.modules.map { it.parentId }
    }

    test("a root-level pyproject.toml does not adopt unrelated top-level modules from a different build system") {
        val root = tempRepo()
        root.resolve("pyproject.toml").writeText(
            """
            [tool.poetry]
            name = "tooling"
            version = "0.1.0"
            """.trimIndent()
        )
        root.resolve("settings.gradle.kts").writeText(
            """include(":app", ":core")"""
        )

        val tree = ModuleDetector().detect(root, ContextGraphConfig())

        val tooling = tree.modules.first { it.path == "" }
        val app = tree.modules.first { it.path == "app" }
        val core = tree.modules.first { it.path == "core" }

        tooling.buildSystem shouldBe "python"
        app.parentId shouldBe null
        core.parentId shouldBe null
        tree.children(tooling).shouldBeEmpty()
    }

    test("a root pyproject.toml still parents nested pyproject.toml packages from the same build system") {
        val root = tempRepo()
        root.resolve("pyproject.toml").writeText(
            """
            [tool.poetry]
            name = "tooling"
            version = "0.1.0"
            """.trimIndent()
        )
        root.resolve("libs/foo").createDirectories()
        root.resolve("libs/foo/pyproject.toml").writeText(
            """
            [tool.poetry]
            name = "foo"
            version = "0.1.0"
            """.trimIndent()
        )

        val tree = ModuleDetector().detect(root, ContextGraphConfig())

        val tooling = tree.modules.first { it.path == "" }
        val foo = tree.modules.first { it.path == "libs/foo" }

        foo.parentId shouldBe tooling.id
        tree.children(tooling) shouldContainExactly listOf(foo)
    }

    test("modules whose paths merely share a string prefix, like 'app' and 'app-core', are not nested") {
        val root = tempRepo()
        root.resolve("settings.gradle.kts").writeText(
            """include(":app", ":app-core")"""
        )

        val tree = ModuleDetector().detect(root, ContextGraphConfig())

        val app = tree.modules.first { it.path == "app" }
        val appCore = tree.modules.first { it.path == "app-core" }

        appCore.parentId shouldBe null
        tree.children(app).shouldBeEmpty()
    }

    test("a root module does not claim orphan files when unrelated top-level modules exist from another build system") {
        val root = tempRepo()
        root.resolve("pyproject.toml").writeText(
            """
            [tool.poetry]
            name = "tooling"
            version = "0.1.0"
            """.trimIndent()
        )
        root.resolve("settings.gradle.kts").writeText(
            """include(":app", ":core")"""
        )

        val tree = ModuleDetector().detect(root, ContextGraphConfig())

        // Orphan file: not under app/ or core/, and tooling has an unrelated top-level sibling
        // (app, core) so it must not silently absorb this file either.
        tree.ownerOf("README.md") shouldBe null
        tree.ownerOf("app/Bar.kt")?.path shouldBe "app"

        val grouped = tree.filesByModule(listOf("README.md", "app/Bar.kt"))
        grouped.values.flatten() shouldContainExactly listOf("app/Bar.kt")
    }

    test("a sole root module still owns files outside every other boundary when it has no unrelated sibling") {
        val root = tempRepo()
        root.resolve("pyproject.toml").writeText(
            """
            [project]
            name = "demo-py"
            version = "0.1.0"
            """.trimIndent()
        )

        val tree = ModuleDetector().detect(root, ContextGraphConfig())

        tree.ownerOf("README.md")?.path shouldBe ""
    }

    test("a root module still owns orphan files when its only other modules are genuine descendants") {
        val root = tempRepo()
        root.resolve("pyproject.toml").writeText(
            """
            [tool.poetry]
            name = "tooling"
            version = "0.1.0"
            """.trimIndent()
        )
        root.resolve("libs/foo").createDirectories()
        root.resolve("libs/foo/pyproject.toml").writeText(
            """
            [tool.poetry]
            name = "foo"
            version = "0.1.0"
            """.trimIndent()
        )

        val tree = ModuleDetector().detect(root, ContextGraphConfig())

        tree.ownerOf("README.md")?.path shouldBe ""
        tree.ownerOf("libs/foo/Main.py")?.path shouldBe "libs/foo"
    }

    test("a config-declared root does not adopt unrelated config-declared modules") {
        val root = tempRepo()
        val config = ContextGraphConfig(
            moduleRoots = listOf(
                ModuleRootConfig(path = "", name = "whole-repo"),
                ModuleRootConfig(path = "ios/MyApp")
            )
        )

        val tree = ModuleDetector().detect(root, config)

        val wholeRepo = tree.modules.first { it.path == "" }
        val ios = tree.modules.first { it.path == "ios/MyApp" }

        wholeRepo.buildSystem shouldBe "config"
        ios.buildSystem shouldBe "config"
        ios.parentId shouldBe null
        tree.children(wholeRepo).shouldBeEmpty()
    }
})
