package com.example.async

class Fetcher {
    val cachedGreeting: String by lazy {
        buildGreeting()
    }

    suspend fun fetch(url: String): String {
        return url
    }

    fun buildGreeting(): String = buildString {
        append("hi")
    }
}
