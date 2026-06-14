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
    val maxFileSizeBytes: Long = 10 * 1024 * 1024
)

@Serializable
data class LiteLlmConfig(
    val baseUrl: String = "http://localhost:4000",
    val model: String = "gpt-4o",
    val rateLimitPerMinute: Int = 10,
    val enabled: Boolean = false,
    val timeoutSeconds: Int = 30
)
