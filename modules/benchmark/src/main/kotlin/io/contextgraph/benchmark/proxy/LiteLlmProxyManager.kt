package io.contextgraph.benchmark.proxy

import io.contextgraph.benchmark.config.Secrets
import java.nio.file.Path

/** What [LiteLlmProxyManager.withProxy] hands its block: where the proxy actually is, and whether this call found one already running (AC-5) or started it itself. */
data class LiteLlmProxyHandle(val baseUrl: String, val alreadyRunning: Boolean)

/**
 * Where the managed proxy's files live and how it's reached. Everything lives under
 * `<repoRoot>/.benchmark-litellm/` -- one gitignored directory holding the venv, the generated
 * config, and the proxy's own log, never anywhere else in the repo and never the system Python.
 */
data class LiteLlmProxyConfig(
    val repoRoot: Path,
    val port: Int = 4000,
    val readinessTimeoutMillis: Long = 60_000,
    val pythonExecutable: String = "/usr/bin/python3"
) {
    private val workDir: Path get() = repoRoot.resolve(".benchmark-litellm")
    val venvDir: Path get() = workDir.resolve("venv")
    val configPath: Path get() = workDir.resolve("config.yaml")
    val logPath: Path get() = workDir.resolve("proxy.log")
    val baseUrl: String get() = "http://localhost:$port"
}

/**
 * The component task 17 asks for: "benchmark'ın kendisi kursun ve ayağa kaldırsın" -- prepares
 * (installing only if needed), starts, health-checks, and tears down a local LiteLLM proxy around
 * exactly the block of work that needs [io.contextgraph.benchmark.judge.LlmJudgeClient], so a
 * benchmark run doesn't depend on a human having set one up by hand first.
 *
 * Every external effect -- reading the API key, HTTP health/port probes, install, process
 * start/stop -- is an injected function with a real-world default (the same
 * [io.contextgraph.benchmark.runner.GuardedBashExecutor] pattern this module already uses for
 * its own external effects), so [LiteLlmProxyManagerTest] can drive every branch (all five loud
 * failures, the reuse-an-existing-proxy path, and the happy path's exact ordering) without ever
 * installing anything, opening a socket, or spawning a process.
 */
class LiteLlmProxyManager(
    private val resolveApiKey: () -> String? = { Secrets.resolve(Secrets.OPENAI_API_KEY) },
    private val isHealthy: (String) -> Boolean = LiteLlmProxyLifecycle::isHealthy,
    private val isPortOpen: (Int) -> Boolean = LiteLlmProxyLifecycle::isPortOpen,
    private val ensureInstalled: (Path, String) -> Unit =
        { venvDir, pythonExecutable -> LiteLlmInstaller.ensureInstalled(venvDir, pythonExecutable) },
    private val writeConfig: (Path, List<String>) -> Unit =
        { path, models -> LiteLlmConfigGenerator.writeTo(path, models) },
    private val startProcess: (Path, Path, Int, String, Path) -> Process = LiteLlmProxyLifecycle::start,
    private val waitUntilReady: (String, Long) -> Unit =
        { baseUrl, timeoutMillis -> LiteLlmProxyLifecycle.waitUntilReady(baseUrl, timeoutMillis) },
    private val stopProcess: (Process) -> Unit = LiteLlmProxyLifecycle::stop
) {
    /**
     * Runs [block] against a ready LiteLLM proxy at [config]'s `baseUrl`, then always tears down
     * whatever this call itself started (never a proxy it merely found and reused) -- even if
     * [block] throws, and even if the proxy never becomes ready (task 17: "Koşu hata alsa bile
     * proxy kapatılmalı").
     *
     * Order: resolve the key (AC "API anahtarı yok" -- loud, first, before touching anything
     * else) -> reuse an already-healthy proxy if one exists (AC-5, no install/start at all) ->
     * else refuse loudly if the port is taken by something else (AC "Port meşgul", before any
     * install is attempted) -> install (idempotent, python-version-checked first) -> write config
     * -> start -> wait for real readiness -> run block -> stop.
     */
    fun <T> withProxy(models: List<String>, config: LiteLlmProxyConfig, block: (LiteLlmProxyHandle) -> T): T {
        val apiKey = resolveApiKey() ?: throw MissingApiKeyException(
            Secrets.missingMessage(Secrets.OPENAI_API_KEY) +
                " The LiteLLM proxy needs it to reach OpenAI on the judge's behalf."
        )

        if (isHealthy(config.baseUrl)) {
            return block(LiteLlmProxyHandle(config.baseUrl, alreadyRunning = true))
        }

        if (isPortOpen(config.port)) {
            throw ProxyPortInUseException(
                "Port ${config.port} is already in use by something that is not answering " +
                    "${config.baseUrl}/health/liveliness as a LiteLLM proxy. Free the port, or " +
                    "point the benchmark at the existing service if it already is one, before " +
                    "running again."
            )
        }

        ensureInstalled(config.venvDir, config.pythonExecutable)
        writeConfig(config.configPath, models)

        val process = startProcess(config.venvDir, config.configPath, config.port, apiKey, config.logPath)
        try {
            waitUntilReady(config.baseUrl, config.readinessTimeoutMillis)
            return block(LiteLlmProxyHandle(config.baseUrl, alreadyRunning = false))
        } finally {
            stopProcess(process)
        }
    }
}
