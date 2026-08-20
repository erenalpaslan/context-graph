package io.contextgraph.benchmark.proxy

/**
 * The five loud failure modes task 17 requires (never a silent fallback or a swallowed
 * exception): a missing API key, an incompatible Python, a failed install, a busy port, and a
 * proxy that never became ready. Each is its own subtype so a caller (or a test) can catch and
 * assert on exactly one without string-matching a message.
 */
sealed class LiteLlmProxyException(message: String) : RuntimeException(message)

/** [io.contextgraph.benchmark.config.Secrets] had no `ANTHROPIC_API_KEY` to give the proxy. */
class MissingApiKeyException(message: String) : LiteLlmProxyException(message)

/** The Python interpreter that would run litellm is outside [LiteLlmPin]'s supported range. */
class UnsupportedPythonVersionException(message: String) : LiteLlmProxyException(message)

/** Creating the venv or `pip install`ing the pinned litellm release failed. */
class LiteLlmInstallException(message: String) : LiteLlmProxyException(message)

/** The configured port is already bound by something that isn't answering as a LiteLLM proxy. */
class ProxyPortInUseException(message: String) : LiteLlmProxyException(message)

/** The proxy process started but never answered its health check within the timeout. */
class ProxyNotReadyException(message: String) : LiteLlmProxyException(message)
