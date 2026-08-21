package io.contextgraph.benchmark.retrieval

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * [RipgrepQueryDeriver] is the single place AC-25 requires the ripgrep query to be derived from
 * a question's text -- this test is where its fairness properties are pinned down: no raw
 * sentence handed to `rg` verbatim, no English prose mistaken for a search term, and no
 * identifier-shaped term missed.
 */
class RipgrepQueryDeriverTest : FunSpec({

    test("plain English words are never treated as search terms") {
        val tokens = RipgrepQueryDeriver.deriveTokens(
            "How does a request travel from the middleware chain to a route's final handler?"
        )
        tokens.shouldNotContain("How")
        tokens.shouldNotContain("does")
        tokens.shouldNotContain("request")
        tokens.shouldNotContain("travel")
        tokens.shouldNotContain("route")
        tokens.shouldNotContain("handler")
    }

    test("apostrophes are never treated as quote delimiters -- regression for the clause-swallowing bug") {
        // An earlier version paired the first apostrophe in the sentence with the next one
        // anywhere later in the text and captured the entire clause between them as "quoted".
        val text = "cal.com's self-hosting license-key doc describes needing a purchased " +
            "License Key but never names it. Within cal.com's process.env, what is it?"
        val tokens = RipgrepQueryDeriver.deriveTokens(text)
        tokens.none { it.length > 40 } shouldBe true
        tokens shouldContain "cal.com"
        tokens shouldContain "process.env"
    }

    test("camelCase and PascalCase identifiers are extracted") {
        val tokens = RipgrepQueryDeriver.deriveTokens("What does handleHTTPRequest call, and what does ServeHTTP do?")
        tokens shouldContain "handleHTTPRequest"
        tokens shouldContain "ServeHTTP"
    }

    test("snake_case and ALL_CAPS identifiers are extracted") {
        val tokens = RipgrepQueryDeriver.deriveTokens("Which environment variable, GIN_MODE, controls test_mode?")
        tokens shouldContain "GIN_MODE"
        tokens shouldContain "test_mode"
    }

    test("dotted symbol references yield both the dotted form and any internally-capitalized part") {
        val tokens = RipgrepQueryDeriver.deriveTokens("What role does Context.Next() play in Engine.ServeHTTP?")
        tokens shouldContain "Context.Next"
        tokens shouldContain "Engine.ServeHTTP"
        // ServeHTTP has an internal capital (drop-first still has upper-case letters) -> qualifies alone.
        tokens shouldContain "ServeHTTP"
        // "Engine", "Context" and "Next" alone are single-leading-capital words indistinguishable
        // from ordinary capitalized English prose (no internal capital, no _, no ., no /) -- an
        // acknowledged, documented limit of this heuristic, not something this function fakes.
        tokens.shouldNotContain("Engine")
        tokens.shouldNotContain("Context")
        tokens.shouldNotContain("Next")
    }

    test("a full file path yields the path, the filename, and the bare symbol name") {
        val tokens = RipgrepQueryDeriver.deriveTokens(
            "In packages/features/bookings/lib/handleCancelBooking.ts, what are the error messages?"
        )
        tokens shouldContain "packages/features/bookings/lib/handleCancelBooking.ts"
        tokens shouldContain "handleCancelBooking.ts"
        tokens shouldContain "handleCancelBooking"
        // generic path segments are not themselves code-shaped
        tokens.shouldNotContain("packages")
        tokens.shouldNotContain("features")
        tokens.shouldNotContain("lib")
    }

    test("bare numeric literals of 2+ digits are extracted") {
        val tokens = RipgrepQueryDeriver.deriveTokens(
            "What is the literal response body for 404 versus 405?"
        )
        tokens shouldContain "404"
        tokens shouldContain "405"
    }

    test("a single stray digit is not extracted") {
        val tokens = RipgrepQueryDeriver.deriveTokens("What does the 1st argument do?")
        tokens.shouldNotContain("1")
    }

    test("quoted literal spans are extracted verbatim") {
        val tokens = RipgrepQueryDeriver.deriveTokens("""What does the constant "404 page not found" appear in?""")
        tokens shouldContain "404 page not found"
    }

    test("Latin abbreviations are excluded even though they contain a dot") {
        val tokens = RipgrepQueryDeriver.deriveTokens("What happens on error, e.g. a timeout, i.e. a network failure?")
        tokens.shouldNotContain("e.g")
        tokens.shouldNotContain("i.e")
    }

    test("a purely conceptual question with no code-shaped words yields no tokens") {
        val tokens = RipgrepQueryDeriver.deriveTokens(
            "Trace the call chain from a Ctrl+Z keydown in the editor through to History " +
                "actually popping an entry off the undo stack."
        )
        tokens shouldBe emptyList()
    }

    test("deterministic: the same question text always yields the same tokens in the same order") {
        val text = "How does Engine.ServeHTTP use Context.Next() and the GIN_MODE env var?"
        val first = RipgrepQueryDeriver.deriveTokens(text)
        val second = RipgrepQueryDeriver.deriveTokens(text)
        first shouldContainExactly second
    }
})
