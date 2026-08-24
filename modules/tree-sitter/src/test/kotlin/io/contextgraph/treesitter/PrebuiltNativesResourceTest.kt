package io.contextgraph.treesitter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull

/**
 * Guards the `prebuilt-natives/` resources source directory (spec
 * `release-distribution-chain`, AC-1).
 *
 * `compileTreeSitterGrammars` only ever compiles grammars for the platform the build runs
 * on, so a jar that serves both macOS arm64 and Linux x64 has to be assembled from two
 * runners: one compiles its own natives, the other's are downloaded as a CI artifact and
 * dropped into `prebuilt-natives/`. That directory is wired in as a *second* resources
 * source directory rather than written into `compileTreeSitterGrammars`' declared output
 * directory, because Gradle's stale-output cleanup is entitled to delete foreign files it
 * finds in a task's outputs.
 *
 * If that wiring is ever dropped, nothing fails loudly: the release still produces a jar,
 * it still works on Linux, and it breaks only on the *other* platform, at
 * [NativeGrammarLoader.load] time, in a user's terminal. This test converts that silent
 * failure into a red build by asserting a committed marker inside `prebuilt-natives/`
 * reaches the classpath the same way a real native would.
 */
class PrebuiltNativesResourceTest : FunSpec({

    test("files placed in prebuilt-natives are packaged as classpath resources") {
        NativeGrammarLoader::class.java.getResource("/lib/.prebuilt-natives-marker")
            .shouldNotBeNull()
    }
})
