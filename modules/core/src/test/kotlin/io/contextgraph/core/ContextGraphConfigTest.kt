package io.contextgraph.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class ContextGraphConfigTest : FunSpec({

    test("moduleRoots defaults to empty") {
        ContextGraphConfig().moduleRoots shouldBe emptyList()
    }

    test("moduleRoots roundtrips through JSON") {
        val json = Json { ignoreUnknownKeys = true }
        val config = ContextGraphConfig(
            moduleRoots = listOf(
                ModuleRootConfig(path = "ios/App", name = "App")
            )
        )
        val encoded = json.encodeToString(ContextGraphConfig.serializer(), config)
        val decoded = json.decodeFromString(ContextGraphConfig.serializer(), encoded)
        decoded.moduleRoots shouldBe listOf(ModuleRootConfig(path = "ios/App", name = "App"))
    }

    test("ModuleRootConfig name defaults to null when omitted") {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(
            ContextGraphConfig.serializer(),
            """{"moduleRoots":[{"path":"ios/App"}]}"""
        )
        decoded.moduleRoots shouldBe listOf(ModuleRootConfig(path = "ios/App", name = null))
    }
})
