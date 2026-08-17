package io.contextgraph.extractors

import io.contextgraph.extractors.snapshot.GoldenSnapshotHarness
import io.contextgraph.extractors.snapshot.RepoRoot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Regression test for a production bug: a source file containing non-ASCII characters
 * could be silently dropped from the graph entirely.
 *
 * Root cause: tree-sitter reports every [io.github.treesitter.ktreesitter.Node]'s position
 * as a **byte** offset into the file's UTF-8 encoding, but ktreesitter's own `Node.text()`
 * slices the char-indexed Kotlin `String` with those byte offsets -- correct only for pure
 * ASCII source, where "byte" and "char" coincide. Every non-ASCII character shifts every
 * subsequent offset out of alignment; once the accumulated drift pushes a late node's byte
 * offset past the source string's char length, `String.substring` throws
 * `StringIndexOutOfBoundsException` and `IngestPipeline` discards the *whole* file's
 * extraction (nodes included) -- this was first observed indexing this repo's own
 * `cli/Main.kt` (em-dashes and an arrow in comments/help strings), which came back with
 * zero nodes.
 *
 * `test-fixtures/non-ascii-regression/Probe.kt` reproduces the exact shape: `earlyFn`
 * (and its `helper()` call) sit before any non-ASCII content and would extract fine even
 * on the broken path; ten em-dashes in a comment further down accumulate twenty bytes of
 * byte/char drift, which is more than enough for `lateFn`'s `target()` call -- a genuinely
 * late node -- to have a tree-sitter-reported byte offset past the file's actual character
 * length (310 chars, but the drifted offset lands at 325+). See `LanguageSupport.kt`'s
 * [io.contextgraph.treesitter.SourceText]/[io.contextgraph.treesitter.textIn] for the fix.
 */
class NonAsciiByteOffsetRegressionTest : FunSpec({

    test("a file with non-ASCII characters before a late call site still extracts all its symbols") {
        val fixtureRoot = RepoRoot.fixture("non-ascii-regression")

        // Pre-fix, this line itself throws StringIndexOutOfBoundsException (from inside
        // TreeSitterExtractor -> KotlinSymbolExtractor.collectReferences/calleeName) and
        // the whole file's extraction -- not just the drifted node -- is lost.
        val extracted = GoldenSnapshotHarness.extractFixture(fixtureRoot)

        val fileNode = extracted.nodes.firstOrNull { it.id.value == "Probe.kt" }
        (fileNode != null) shouldBe true

        val earlyFn = extracted.nodes.firstOrNull { it.id.value == "Probe.kt#earlyFn()" }
        (earlyFn != null) shouldBe true
        earlyFn?.label shouldBe "earlyFn"

        // The late declaration, positioned after the non-ASCII comment. Walking its body
        // for call sites (`target()`) is exactly where the pre-fix code threw
        // StringIndexOutOfBoundsException -- the drifted byte offset belongs to this call,
        // deep inside `lateFn`, not to anything near the file's non-ASCII comment itself.
        val lateFn = extracted.nodes.firstOrNull { it.id.value == "Probe.kt#lateFn()" }
        (lateFn != null) shouldBe true
        lateFn?.label shouldBe "lateFn"

        extracted.diagnostics shouldBe emptyList()
    }
})
