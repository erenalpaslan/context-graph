import java.io.ByteArrayOutputStream
import java.net.URI

dependencies {
    implementation(project(":modules:core"))
    implementation(libs.ktreesitter)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
}

/**
 * One entry per vendored tree-sitter grammar. This list is the single place all seven
 * grammars (eight registry entries: TypeScript and TSX are two grammars from one repo)
 * are declared and version-pinned. Language-specific slices (04-08) must not edit this
 * file — they own exactly one file under `src/main/kotlin/.../grammars/`.
 *
 * [repo] + [commit] are fetched from GitHub (codeload tarball) and compiled from source
 * on whichever platform the build runs on (macOS arm64 / Linux x64 are the two CI/dev
 * platforms this project targets). Two grammars sharing a [repo]+[commit] pair (i.e.
 * typescript/tsx) are downloaded once and compiled twice, from different subdirectories.
 */
data class GrammarSpec(
    /** Short id, used only for logging. */
    val id: String,
    /** `owner/repo` on GitHub. */
    val repo: String,
    /** Pinned commit SHA (never a floating branch/tag) that `src/parser.c` is generated at. */
    val commit: String,
    /** Directory inside the extracted tarball containing `parser.c` (and `scanner.c`). */
    val srcSubdir: String = "src",
    /** The grammar's exported C entry point, e.g. `tree_sitter_java`. */
    val languageFn: String,
    /**
     * Fully-qualified JNI binding class the compiled native library exports its
     * `language()` native method against, e.g. `io.contextgraph.treesitter.grammars.TreeSitterJava`.
     * Must match the Kotlin `object` declared in that language's grammar file exactly.
     */
    val jniClass: String,
    /** Whether `scanner.c` exists alongside `parser.c` and must be compiled in too. */
    val hasScanner: Boolean = true,
    /** Base name of the compiled native library, e.g. `contextgraph_ts_java`. */
    val libName: String,
)

val grammarSpecs = listOf(
    GrammarSpec(
        id = "java",
        repo = "tree-sitter/tree-sitter-java",
        commit = "94703d5a6bed02b98e438d7cad1136c01a60ba2c", // v0.23.5
        languageFn = "tree_sitter_java",
        jniClass = "io.contextgraph.treesitter.grammars.TreeSitterJava",
        hasScanner = false,
        libName = "contextgraph_ts_java",
    ),
    GrammarSpec(
        id = "python",
        repo = "tree-sitter/tree-sitter-python",
        // v0.23.6, not the newer v0.25.0: that tag generates ABI 15, which is newer than
        // ktreesitter 0.24.1's native runtime (pinned for Kotlin-metadata compatibility,
        // see libs.versions.toml) accepts. v0.23.6 generates ABI 14, matching every other
        // grammar here.
        commit = "bffb65a8cfe4e46290331dfef0dbf0ef3679de11",
        languageFn = "tree_sitter_python",
        jniClass = "io.contextgraph.treesitter.grammars.TreeSitterPython",
        libName = "contextgraph_ts_python",
    ),
    GrammarSpec(
        id = "javascript",
        repo = "tree-sitter/tree-sitter-javascript",
        // v0.23.1, not the newer v0.25.0, for the same ABI-14-vs-15 reason as python above.
        commit = "3a837b6f3658ca3618f2022f8707e29739c91364",
        languageFn = "tree_sitter_javascript",
        jniClass = "io.contextgraph.treesitter.grammars.TreeSitterJavaScript",
        libName = "contextgraph_ts_javascript",
    ),
    GrammarSpec(
        id = "typescript",
        repo = "tree-sitter/tree-sitter-typescript",
        commit = "f975a621f4e7f532fe322e13c4f79495e0a7b2e7", // v0.23.2
        srcSubdir = "typescript/src",
        languageFn = "tree_sitter_typescript",
        jniClass = "io.contextgraph.treesitter.grammars.TreeSitterTypeScript",
        libName = "contextgraph_ts_typescript",
    ),
    GrammarSpec(
        id = "tsx",
        repo = "tree-sitter/tree-sitter-typescript",
        commit = "f975a621f4e7f532fe322e13c4f79495e0a7b2e7", // v0.23.2 (same repo/commit as typescript)
        srcSubdir = "tsx/src",
        languageFn = "tree_sitter_tsx",
        jniClass = "io.contextgraph.treesitter.grammars.TreeSitterTsx",
        libName = "contextgraph_ts_tsx",
    ),
    GrammarSpec(
        id = "kotlin",
        repo = "fwcd/tree-sitter-kotlin", // community grammar, not the kotlin-tree-sitter bindings
        commit = "e1a2d5ad1f61f5740677183cd4125bb071cd2f30", // 0.3.8
        languageFn = "tree_sitter_kotlin",
        jniClass = "io.contextgraph.treesitter.grammars.TreeSitterKotlin",
        libName = "contextgraph_ts_kotlin",
    ),
    GrammarSpec(
        id = "swift",
        repo = "alex-pinkus/tree-sitter-swift", // community grammar, not tree-sitter/swift-tree-sitter (a Swift binding)
        commit = "31d17fe7e818a2048c808b5c6fdc2dc792f4f5b5", // 0.7.3-with-generated-files
        languageFn = "tree_sitter_swift",
        jniClass = "io.contextgraph.treesitter.grammars.TreeSitterSwift",
        libName = "contextgraph_ts_swift",
    ),
    GrammarSpec(
        id = "objc",
        repo = "tree-sitter-grammars/tree-sitter-objc",
        commit = "18802acf31d0b5c1c1d50bdbc9eb0e1636cab9ed", // v3.0.2
        languageFn = "tree_sitter_objc",
        jniClass = "io.contextgraph.treesitter.grammars.TreeSitterObjectiveC",
        hasScanner = false,
        libName = "contextgraph_ts_objc",
    ),
)

/** Host OS/arch detection, mirroring the layout ktreesitter's own jar resources use. */
data class HostPlatform(val osDir: String, val arch: String, val ext: String)

fun currentHostPlatform(): HostPlatform {
    val osName = System.getProperty("os.name").lowercase()
    val archName = System.getProperty("os.arch").lowercase()
    val os = when {
        "linux" in osName -> "linux"
        "mac" in osName -> "macos"
        else -> throw GradleException(
            "Unsupported OS for compiling tree-sitter grammar natives: $osName. " +
                "This project only supports macOS arm64 and Linux x64."
        )
    }
    val arch = when {
        "aarch64" in archName || "arm64" in archName -> "aarch64"
        "amd64" in archName || "x86_64" in archName -> "x64"
        else -> throw GradleException(
            "Unsupported architecture for compiling tree-sitter grammar natives: $archName. " +
                "This project only supports macOS arm64 and Linux x64."
        )
    }
    return HostPlatform(os, arch, if (os == "macos") "dylib" else "so")
}

val grammarSrcCache = layout.buildDirectory.dir("tree-sitter-src")
val grammarDownloadCache = layout.buildDirectory.dir("tree-sitter-download")
val nativesOutputDir = layout.buildDirectory.dir("generated/tree-sitter-natives")

val compileTreeSitterGrammars = tasks.register("compileTreeSitterGrammars") {
    group = "tree-sitter"
    description = "Downloads pinned tree-sitter grammar sources and compiles each into a " +
        "native library exposing a JNI language() binding, for the current host platform."

    val srcCacheDir = grammarSrcCache.get().asFile
    val downloadCacheDir = grammarDownloadCache.get().asFile
    val outDir = nativesOutputDir.get().asFile
    outputs.dir(nativesOutputDir)

    doLast {
        val platform = currentHostPlatform()
        val jdkHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        }.get().metadata.installationPath.asFile
        val jniIncludeOs = if (platform.osDir == "macos") "darwin" else "linux"

        srcCacheDir.mkdirs()
        downloadCacheDir.mkdirs()
        val libOutDir = outDir.resolve("lib/${platform.osDir}/${platform.arch}")
        libOutDir.mkdirs()

        // Download + extract each distinct (repo, commit) once, even though typescript/tsx share one.
        val extractedDirs = mutableMapOf<String, java.io.File>()
        for (spec in grammarSpecs) {
            val key = "${spec.repo}@${spec.commit}"
            extractedDirs.getOrPut(key) {
                val safeName = key.replace("/", "_").replace(":", "_")
                val extractDir = srcCacheDir.resolve(safeName)
                val markerFile = extractDir.resolve(".extracted")
                if (!markerFile.exists()) {
                    val tarball = downloadCacheDir.resolve("$safeName.tar.gz")
                    if (!tarball.exists()) {
                        logger.lifecycle("tree-sitter: downloading ${spec.repo}@${spec.commit}")
                        val url = URI("https://codeload.github.com/${spec.repo}/tar.gz/${spec.commit}").toURL()
                        url.openStream().use { input ->
                            tarball.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    extractDir.deleteRecursively()
                    extractDir.mkdirs()
                    copy {
                        from(tarTree(tarball))
                        into(extractDir)
                    }
                    // GitHub tarballs wrap everything in a single top-level "<repo>-<sha>" directory.
                    val nested = extractDir.listFiles()?.singleOrNull { it.isDirectory }
                        ?: throw GradleException("Unexpected tarball layout for ${spec.repo}@${spec.commit}")
                    for (child in nested.listFiles().orEmpty()) {
                        child.copyRecursively(extractDir.resolve(child.name), overwrite = true)
                    }
                    nested.deleteRecursively()
                    markerFile.writeText("ok")
                }
                extractDir
            }
        }

        for (spec in grammarSpecs) {
            val libFile = libOutDir.resolve("lib${spec.libName}.${platform.ext}")
            if (libFile.exists()) continue

            val key = "${spec.repo}@${spec.commit}"
            val grammarSrcDir = extractedDirs.getValue(key).resolve(spec.srcSubdir)
            val parserC = grammarSrcDir.resolve("parser.c")
            check(parserC.exists()) { "No parser.c for ${spec.id} at $parserC" }
            val scannerC = grammarSrcDir.resolve("scanner.c")

            val shimC = temporaryDir.resolve("${spec.id}-shim.c")
            shimC.writeText(
                """
                #include <jni.h>
                #include <stdint.h>

                typedef void TSLanguage;
                extern const TSLanguage *${spec.languageFn}(void);

                JNIEXPORT jlong JNICALL JNI_METHOD_NAME(JNIEnv *env, jclass clazz) {
                    (void) env;
                    (void) clazz;
                    return (jlong)(intptr_t) ${spec.languageFn}();
                }
                """.trimIndent()
            )

            // JNI's native-symbol mangling is `Java_<pkg>_<Class>_<method>` with dots replaced
            // by underscores (and literal underscores in identifiers escaped as "_1" -- none of
            // our package/class names contain one, so that escaping never triggers here).
            val jniMethodName = "Java_${spec.jniClass.replace(".", "_")}_language"

            val args = mutableListOf(
                "cc", "-shared", "-fPIC", "-O1",
                "-DJNI_METHOD_NAME=$jniMethodName",
                "-I", jdkHome.resolve("include").absolutePath,
                "-I", jdkHome.resolve("include/$jniIncludeOs").absolutePath,
                "-I", grammarSrcDir.absolutePath,
                shimC.absolutePath,
                parserC.absolutePath,
            )
            if (spec.hasScanner) {
                check(scannerC.exists()) { "hasScanner=true but no scanner.c for ${spec.id} at $scannerC" }
                args.add(scannerC.absolutePath)
            }
            args.addAll(listOf("-o", libFile.absolutePath))

            logger.lifecycle("tree-sitter: compiling ${spec.id} -> ${libFile.relativeTo(outDir)}")
            val stdout = ByteArrayOutputStream()
            val result = exec {
                commandLine(args)
                standardOutput = stdout
                errorOutput = stdout
                isIgnoreExitValue = true
            }
            if (result.exitValue != 0) {
                throw GradleException(
                    "Failed to compile tree-sitter grammar '${spec.id}' (exit ${result.exitValue}):\n" +
                        stdout.toString()
                )
            }
        }
    }
}

sourceSets {
    main {
        resources.srcDir(nativesOutputDir)
    }
}

tasks.named("processResources") {
    dependsOn(compileTreeSitterGrammars)
}
