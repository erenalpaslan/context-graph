package io.contextgraph.benchmark.proxy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

/**
 * [LiteLlmProxyManager.withProxy] is the component task 17 asks for: prepare, start,
 * health-check, run, tear down -- around exactly the block of work that needs a judge. Every
 * external effect (reading the key, HTTP probes, install, process start/stop) is injected here,
 * so this proves the *orchestration* -- ordering, which of the five loud failures fires when, and
 * that teardown always runs -- without a real key, process, or network call. See task 17's own
 * report for the real end-to-end proof (a live proxy started, health-checked, and torn down for
 * real against a real Anthropic backend).
 */
class LiteLlmProxyManagerTest : FunSpec({

    fun config() = LiteLlmProxyConfig(repoRoot = Path.of("/tmp/litellm-proxy-manager-test-repo"))

    fun manager(
        resolveApiKey: () -> String? = { "sk-ant-test-key" },
        isHealthy: (String) -> Boolean = { false },
        isPortOpen: (Int) -> Boolean = { false },
        ensureInstalled: (Path, String) -> Unit = { _, _ -> },
        writeConfig: (Path, List<String>) -> Unit = { _, _ -> },
        startProcess: (Path, Path, Int, String, Path) -> Process = { _, _, _, _, _ -> FakeProxyProcess() },
        waitUntilReady: (String, Long) -> Unit = { _, _ -> },
        stopProcess: (Process) -> Unit = {}
    ) = LiteLlmProxyManager(resolveApiKey, isHealthy, isPortOpen, ensureInstalled, writeConfig, startProcess, waitUntilReady, stopProcess)

    test("missing API key throws MissingApiKeyException before any install, start, or health probe") {
        var installCalled = false
        val exception = shouldThrow<MissingApiKeyException> {
            manager(
                resolveApiKey = { null },
                ensureInstalled = { _, _ -> installCalled = true }
            ).withProxy(listOf("claude-opus-5"), config()) { }
        }
        exception.message shouldContain "OPENAI_API_KEY"
        installCalled shouldBe false
    }

    test("reuses an already-healthy proxy: never installs, never starts, never stops anything") {
        var installCalled = false
        var startCalled = false
        var stopCalled = false

        val handleSeen = manager(
            isHealthy = { true },
            ensureInstalled = { _, _ -> installCalled = true },
            startProcess = { _, _, _, _, _ -> startCalled = true; FakeProxyProcess() },
            stopProcess = { stopCalled = true }
        ).withProxy(listOf("claude-opus-5"), config()) { handle -> handle }

        handleSeen.alreadyRunning shouldBe true
        handleSeen.baseUrl shouldBe "http://localhost:4000"
        installCalled shouldBe false
        startCalled shouldBe false
        stopCalled shouldBe false
    }

    test("an unhealthy-but-open port throws ProxyPortInUseException before any install is attempted") {
        var installCalled = false
        val exception = shouldThrow<ProxyPortInUseException> {
            manager(
                isHealthy = { false },
                isPortOpen = { true },
                ensureInstalled = { _, _ -> installCalled = true }
            ).withProxy(listOf("claude-opus-5"), config()) { }
        }
        exception.message shouldContain "4000"
        installCalled shouldBe false
    }

    test("happy path: installs, writes config, starts, waits for ready, runs the block, then stops") {
        val calls = mutableListOf<String>()
        val process = FakeProxyProcess()

        val handleSeen = manager(
            ensureInstalled = { _, _ -> calls.add("install") },
            writeConfig = { _, models -> calls.add("config:$models") },
            startProcess = { _, _, _, _, _ -> calls.add("start"); process },
            waitUntilReady = { _, _ -> calls.add("ready") },
            stopProcess = { p -> calls.add("stop"); p shouldBe process }
        ).withProxy(listOf("claude-opus-5"), config()) { handle -> calls.add("block"); handle }

        calls shouldBe listOf("install", "config:[claude-opus-5]", "start", "ready", "block", "stop")
        handleSeen.alreadyRunning shouldBe false
        handleSeen.baseUrl shouldBe "http://localhost:4000"
    }

    test("stops the process even when the block itself throws") {
        var stopCalled = false
        val exception = shouldThrow<RuntimeException> {
            manager(stopProcess = { stopCalled = true }).withProxy(listOf("claude-opus-5"), config()) {
                throw RuntimeException("agent code blew up")
            }
        }
        exception.message shouldBe "agent code blew up"
        stopCalled shouldBe true
    }

    test("stops the process even when readiness times out, and the timeout still propagates") {
        var stopCalled = false
        var blockCalled = false
        shouldThrow<ProxyNotReadyException> {
            manager(
                waitUntilReady = { _, _ -> throw ProxyNotReadyException("never answered") },
                stopProcess = { stopCalled = true }
            ).withProxy(listOf("claude-opus-5"), config()) { blockCalled = true }
        }
        stopCalled shouldBe true
        blockCalled shouldBe false
    }

    test("an unsupported Python propagates from ensureInstalled without ever starting a process") {
        var startCalled = false
        shouldThrow<UnsupportedPythonVersionException> {
            manager(
                ensureInstalled = { _, _ -> throw UnsupportedPythonVersionException("Python 3.8 too old") },
                startProcess = { _, _, _, _, _ -> startCalled = true; FakeProxyProcess() }
            ).withProxy(listOf("claude-opus-5"), config()) { }
        }
        startCalled shouldBe false
    }

    test("a failed install propagates from ensureInstalled without ever starting a process") {
        var startCalled = false
        shouldThrow<LiteLlmInstallException> {
            manager(
                ensureInstalled = { _, _ -> throw LiteLlmInstallException("pip failed, no network") },
                startProcess = { _, _, _, _, _ -> startCalled = true; FakeProxyProcess() }
            ).withProxy(listOf("claude-opus-5"), config()) { }
        }
        startCalled shouldBe false
    }
})

/** A [Process] double standing in for a started (but never real) litellm proxy. */
private class FakeProxyProcess : Process() {
    override fun isAlive(): Boolean = true
    override fun destroy() {}
    override fun destroyForcibly(): Process = this
    override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true
    override fun getOutputStream() = throw UnsupportedOperationException()
    override fun getInputStream() = throw UnsupportedOperationException()
    override fun getErrorStream() = throw UnsupportedOperationException()
    override fun waitFor(): Int = 0
    override fun exitValue(): Int = 0
}
