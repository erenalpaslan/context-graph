// Slice 19 (AC-34): the task eval harness. Depends on mcp-server for ExploreEngine itself
// (the thing being measured), ingest for LiteLlmModuleEmbedder (module search fallback
// ExploreEngine wires through), storage-sqlite to open a graph.db/graph.local.db read-only,
// and core for ContextGraphConfig. Deliberately not an `application` producing a long-running
// service -- `main()` runs one eval pass and exits, so `run` is the whole re-run story.
plugins {
    application
}

application {
    mainClass.set("io.contextgraph.eval.MainKt")
}

dependencies {
    implementation(project(":modules:core"))
    implementation(project(":modules:ingest"))
    implementation(project(":modules:mcp-server"))
    implementation(project(":modules:storage-sqlite"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.logging)
    // LiteLlmModuleEmbedder()'s default constructor argument type (HttpClientEngine, from
    // ktor-client-cio) must be resolvable on this module's own compile classpath -- ingest's
    // dependency on it is `implementation`-scoped and does not leak transitively. Same
    // requirement, same fix, as modules/cli/build.gradle.kts.
    implementation(libs.ktor.client.cio)
    runtimeOnly(libs.logback)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
}
