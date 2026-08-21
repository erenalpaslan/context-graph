package io.contextgraph.benchmark.proxy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [PythonVersion.parse] reads the real, measured output of `python3 --version` (task 17's
 * environment facts: "Python 3.9.6" on this machine's system Python). Parsing is pure and
 * offline -- no interpreter is ever invoked by this test.
 */
class PythonVersionTest : FunSpec({
    test("parses 'Python 3.9.6' as measured on this machine's system Python") {
        PythonVersion.parse("Python 3.9.6") shouldBe PythonVersion(3, 9, 6)
    }

    test("parses a trailing-newline version string, the real shape of captured process stdout") {
        PythonVersion.parse("Python 3.13.1\n") shouldBe PythonVersion(3, 13, 1)
    }

    test("ordering compares major, then minor, then patch") {
        (PythonVersion(3, 9, 6) < PythonVersion(3, 10, 0)) shouldBe true
        (PythonVersion(3, 9, 6) < PythonVersion(3, 9, 7)) shouldBe true
        (PythonVersion(3, 14, 0) < PythonVersion(3, 9, 6)) shouldBe false
    }

    test("unparseable output throws with the offending text in the message") {
        val exception = shouldThrow<IllegalArgumentException> {
            PythonVersion.parse("command not found: python3")
        }
        exception.message shouldBe "Could not parse a Python version out of: 'command not found: python3'"
    }
})
