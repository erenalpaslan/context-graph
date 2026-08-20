package io.contextgraph.benchmark.proxy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * [LiteLlmPin] encodes what task 17's own verification found empirically on PyPI (checked
 * 2026-08-17): `litellm==1.83.9` is the newest release whose `requires_python` metadata
 * (`>=3.9,<3.14`) still covers this machine's system Python 3.9.6 -- `1.84.0` and everything
 * after it raised the floor to `>=3.10`. This test proves the *boundary logic* against that
 * measured fact; it makes no network call itself.
 */
class LiteLlmPinTest : FunSpec({
    test("the pinned version is the one actually verified against PyPI metadata and a real install") {
        LiteLlmPin.VERSION shouldBe "1.83.9"
    }

    test("this machine's measured Python (3.9.6) is supported") {
        LiteLlmPin.isSupported(PythonVersion(3, 9, 6)) shouldBe true
    }

    test("a Python older than the pinned release's floor is not supported") {
        LiteLlmPin.isSupported(PythonVersion(3, 8, 10)) shouldBe false
    }

    test("the upper bound is exclusive: 3.14 and above are not supported") {
        LiteLlmPin.isSupported(PythonVersion(3, 14, 0)) shouldBe false
        LiteLlmPin.isSupported(PythonVersion(3, 13, 9)) shouldBe true
    }

    test("requireSupported throws a message naming both the offending version and the required range") {
        val exception = shouldThrow<UnsupportedPythonVersionException> {
            LiteLlmPin.requireSupported(PythonVersion(3, 8, 10))
        }
        exception.message shouldContain "3.8.10"
        exception.message shouldContain "3.9.0"
        exception.message shouldContain "1.83.9"
    }

    test("requireSupported does not throw for a supported version") {
        LiteLlmPin.requireSupported(PythonVersion(3, 9, 6))
    }
})
