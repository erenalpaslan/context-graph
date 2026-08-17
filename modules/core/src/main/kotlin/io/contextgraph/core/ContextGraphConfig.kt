package io.contextgraph.core

import kotlinx.serialization.Serializable

@Serializable
data class ContextGraphConfig(
    val litellm: LiteLlmConfig = LiteLlmConfig(),
    val includePatterns: List<String> = listOf("**/*"),
    val excludePatterns: List<String> = listOf(
        "**/.git/**",
        "**/node_modules/**",
        "**/build/**",
        "**/.gradle/**",
        "**/.contextgraph/**",
        "**/target/**",
        "**/__pycache__/**"
    ),
    val ignoreSecrets: Boolean = true,
    val maxFileSizeBytes: Long = 10 * 1024 * 1024,
    val moduleRoots: List<ModuleRootConfig> = emptyList(),
    val watcher: WatcherConfig = WatcherConfig()
)

/**
 * The opt-in filesystem watcher (slice 13). [enabled] defaults to `false` deliberately --
 * the watcher must never start unless a developer has explicitly turned it on here, and
 * never as a side effect of any other command.
 */
@Serializable
data class WatcherConfig(
    val enabled: Boolean = false,
    /** Debounce window: a burst of filesystem events collapses into one reindex this long after it starts. */
    val debounceMillis: Long = 500,
    /** Fallback rescan interval, independent of watch events -- covers watch registrations silently dropped by the OS. */
    val fallbackIntervalMillis: Long = 30_000
)

/**
 * An explicitly declared module boundary, overriding build-file detection for the same
 * [path]. This is how module boundaries that no build file expresses get in — iOS targets
 * declared in an Xcode project (`.pbxproj` is not parsed) being the motivating case.
 */
@Serializable
data class ModuleRootConfig(
    /** Repo-relative directory, forward-slash separated, no leading or trailing slash. */
    val path: String,
    /** Defaults to the last path segment when omitted. */
    val name: String? = null
)

@Serializable
data class LiteLlmConfig(
    val baseUrl: String = "http://localhost:4000",
    val model: String = "gpt-4o",
    val rateLimitPerMinute: Int = 10,
    val enabled: Boolean = false,
    val timeoutSeconds: Int = 30
)
