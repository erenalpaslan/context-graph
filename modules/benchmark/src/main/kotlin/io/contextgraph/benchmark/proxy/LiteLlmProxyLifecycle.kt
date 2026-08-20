package io.contextgraph.benchmark.proxy

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Starts, health-checks, and stops the actual `litellm` proxy process. Every real-world edge
 * (HTTP, sockets, process control, the clock, sleeping) is a function parameter with a
 * real-world default, so [LiteLlmProxyLifecycleTest] can prove the polling/timeout/shutdown
 * *logic* -- and [LiteLlmProxyManager] can prove its own orchestration -- without a real
 * subprocess, socket, or wall-clock wait. See task 17's own report for the one proof that can't
 * be automated here: actually starting the real proxy and watching it answer within ~1s.
 */
object LiteLlmProxyLifecycle {

    /**
     * True if an HTTP GET to `$baseUrl/health/liveliness` -- litellm's own liveness endpoint,
     * which answers before any model or API key is even validated -- returns 200 within
     * [timeoutMillis]. Never throws: any failure to connect (refused, DNS, timeout) just means
     * "not healthy yet", the same way a caller polling for readiness needs it to.
     */
    fun isHealthy(baseUrl: String, timeoutMillis: Int = 1000): Boolean = try {
        val connection = URI("$baseUrl/health/liveliness").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.requestMethod = "GET"
        val code = connection.responseCode
        connection.disconnect()
        code == 200
    } catch (e: Exception) {
        false
    }

    /** True if *something* is already listening on localhost:[port] -- used to tell "a real LiteLLM is already up" (see [isHealthy]) apart from "some unrelated process has this port" (a loud failure). */
    fun isPortOpen(port: Int, timeoutMillis: Int = 500): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("localhost", port), timeoutMillis) }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Polls [isHealthyFn] every [intervalMillis] until it reports healthy, or throws
     * [ProxyNotReadyException] once [nowMillis] passes the deadline -- never treats "the process
     * started" as "the proxy is ready" (task 17: "süreç başladı diye hazır sayma").
     */
    fun waitUntilReady(
        baseUrl: String,
        timeoutMillis: Long,
        intervalMillis: Long = 500,
        nowMillis: () -> Long = System::currentTimeMillis,
        sleep: (Long) -> Unit = Thread::sleep,
        isHealthyFn: (String) -> Boolean = ::isHealthy
    ) {
        val deadline = nowMillis() + timeoutMillis
        while (nowMillis() < deadline) {
            if (isHealthyFn(baseUrl)) return
            sleep(intervalMillis)
        }
        if (isHealthyFn(baseUrl)) return
        throw ProxyNotReadyException(
            "The LiteLLM proxy at $baseUrl did not answer /health/liveliness within " +
                "${timeoutMillis}ms. Check its log for the real startup error."
        )
    }

    /**
     * Starts `<venvDir>/bin/litellm --config <configPath> --port <port>`. [apiKey] is passed
     * only as the child process's [LiteLlmConfigGenerator.API_KEY_ENV_VAR] environment variable
     * (task 20: `OPENAI_API_KEY`, matching `config.yaml`'s `api_key: os.environ/...` indirection
     * -- referencing that constant rather than a second hard-coded literal here is what keeps the
     * two from drifting apart the way they would have on the provider switch otherwise) -- never
     * as a command-line argument (visible in `ps`) and never written to [configPath] or
     * [logPath]. Both stdout and stderr are redirected to [logPath] (gitignored, never this
     * process's own stdout), since litellm's real proxy is otherwise noisy on startup.
     */
    fun start(venvDir: Path, configPath: Path, port: Int, apiKey: String, logPath: Path): Process {
        logPath.parent?.let { Files.createDirectories(it) }
        val litellmBin = venvDir.resolve("bin/litellm")
        val builder = ProcessBuilder(litellmBin.toString(), "--config", configPath.toString(), "--port", port.toString())
        builder.environment()[LiteLlmConfigGenerator.API_KEY_ENV_VAR] = apiKey
        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()))
        return builder.start()
    }

    /**
     * Stops [process]: a polite `destroy()`, then `destroyForcibly()` if it hasn't exited within
     * [gracePeriodSeconds] -- run in a `finally` by every caller (see [LiteLlmProxyManager]) so a
     * failed run still leaves no process behind (task 17: "Koşu hata alsa bile proxy
     * kapatılmalı"). A no-op if [process] has already exited.
     */
    fun stop(process: Process, gracePeriodSeconds: Long = 5) {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(gracePeriodSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
    }
}
