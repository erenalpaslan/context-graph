package io.contextgraph.benchmark.proxy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

/**
 * [LiteLlmInstaller.ensureInstalled] is the only step that would ever need the network (task 17's
 * note: "Ağ erişimi gerektiren tek adım kurulum"). Every test here injects a fake [runner] so the
 * decision logic -- check Python first, skip if already pinned, install into the venv, surface a
 * failed install loudly -- is proven without ever invoking a real interpreter, venv, or pip.
 *
 * Seam: the `runner` constructor/method parameter [LiteLlmInstaller.ensureInstalled] already
 * exposes, the same injectable-function pattern [GuardedBashExecutor] uses for its
 * `processRunner`.
 */
class LiteLlmInstallerTest : FunSpec({

    test("checks the Python version first and throws before touching venv or pip when unsupported") {
        val calls = mutableListOf<List<String>>()
        val runner: (List<String>, Path?, Map<String, String>) -> ProcessRunResult = { command, _, _ ->
            calls.add(command)
            ProcessRunResult(0, "Python 3.8.10\n", "")
        }

        shouldThrow<UnsupportedPythonVersionException> {
            LiteLlmInstaller.ensureInstalled(Path.of("/tmp/does-not-matter/venv"), "python3", runner)
        }

        calls.size shouldBe 1
        calls.single() shouldBe listOf("python3", "--version")
    }

    test("skips install entirely when the venv already carries the pinned version (idempotent)") {
        val calls = mutableListOf<List<String>>()
        val runner: (List<String>, Path?, Map<String, String>) -> ProcessRunResult = { command, _, _ ->
            calls.add(command)
            when {
                command == listOf("python3", "--version") -> ProcessRunResult(0, "Python 3.9.6\n", "")
                command.contains("show") -> ProcessRunResult(0, "Name: litellm\nVersion: ${LiteLlmPin.VERSION}\n", "")
                else -> throw AssertionError("should not reach: $command")
            }
        }

        LiteLlmInstaller.ensureInstalled(existingVenvDir, "python3", runner)

        calls.none { it.contains("venv") } shouldBe true
        calls.none { it.contains("install") } shouldBe true
    }

    test("creates the venv and installs the pinned version when nothing is installed yet") {
        val calls = mutableListOf<List<String>>()
        val runner: (List<String>, Path?, Map<String, String>) -> ProcessRunResult = { command, _, _ ->
            calls.add(command)
            when {
                command == listOf("python3", "--version") -> ProcessRunResult(0, "Python 3.9.6\n", "")
                command.contains("show") -> ProcessRunResult(1, "", "WARNING: Package(s) not found: litellm")
                command.contains("venv") -> ProcessRunResult(0, "", "")
                command.contains("install") -> ProcessRunResult(0, "Successfully installed litellm-${LiteLlmPin.VERSION}", "")
                else -> throw AssertionError("unexpected command: $command")
            }
        }

        LiteLlmInstaller.ensureInstalled(missingVenvDir, "python3", runner)

        calls shouldContain listOf("python3", "-m", "venv", missingVenvDir.toString())
        calls.any { it.contains("install") && it.any { arg -> arg.contains("litellm[proxy]==${LiteLlmPin.VERSION}") } } shouldBe true
    }

    test("a stale installed version triggers reinstall rather than being treated as already-pinned") {
        val calls = mutableListOf<List<String>>()
        val runner: (List<String>, Path?, Map<String, String>) -> ProcessRunResult = { command, _, _ ->
            calls.add(command)
            when {
                command == listOf("python3", "--version") -> ProcessRunResult(0, "Python 3.9.6\n", "")
                command.contains("show") -> ProcessRunResult(0, "Name: litellm\nVersion: 1.60.0\n", "")
                command.contains("install") -> ProcessRunResult(0, "", "")
                else -> throw AssertionError("unexpected command: $command")
            }
        }

        LiteLlmInstaller.ensureInstalled(existingVenvDir, "python3", runner)

        calls.any { it.contains("install") } shouldBe true
        // Already exists on disk (per this test's fixture) -- must not be recreated.
        calls.none { it.contains("venv") } shouldBe true
    }

    test("a failed pip install throws LiteLlmInstallException carrying the real stderr") {
        val runner: (List<String>, Path?, Map<String, String>) -> ProcessRunResult = { command, _, _ ->
            when {
                command == listOf("python3", "--version") -> ProcessRunResult(0, "Python 3.9.6\n", "")
                command.contains("show") -> ProcessRunResult(1, "", "not found")
                command.contains("venv") -> ProcessRunResult(0, "", "")
                command.contains("install") -> ProcessRunResult(1, "", "ERROR: Could not find a version, no network")
                else -> throw AssertionError("unexpected command: $command")
            }
        }

        val exception = shouldThrow<LiteLlmInstallException> {
            LiteLlmInstaller.ensureInstalled(missingVenvDir, "python3", runner)
        }
        exception.message shouldContain "no network"
    }
}) {
    companion object {
        /** These paths are never touched by the fake runner -- only used as map keys/arguments in assertions. */
        val existingVenvDir: Path = ExistingVenvMarker.dir
        val missingVenvDir: Path = Path.of("/tmp/litellm-installer-test-missing-venv")
    }
}

/**
 * [LiteLlmInstaller.ensureInstalled] decides "is litellm already pinned here" from two real
 * filesystem/process facts, not from anything a fake can shortcut around: `bin/python` must
 * exist inside the venv dir before `pip show` is even attempted. For the "already pinned, skip
 * everything" and "stale version, reinstall in place" tests to exercise that real branch (not
 * silently fall through to "not installed yet" because the fixture is missing that file), this
 * creates one real temp directory with an empty `bin/python` placeholder inside it -- content
 * doesn't matter, only that [java.nio.file.Files.exists] says yes.
 */
private object ExistingVenvMarker {
    val dir: Path = kotlin.io.path.createTempDirectory("litellm-installer-existing-venv").also {
        val binDir = it.resolve("bin")
        java.nio.file.Files.createDirectories(binDir)
        java.nio.file.Files.createFile(binDir.resolve("python"))
    }
}
