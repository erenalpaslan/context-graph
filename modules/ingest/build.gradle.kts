dependencies {
    implementation(project(":modules:core"))
    // Reindex.kt (the shared ReindexPrimitive both `cli` and `mcp-server` call into) needs the
    // concrete production extractor list and a concrete StorageAdapter to open. Neither
    // `modules:extractors` nor `modules:storage-sqlite` depends back on `modules:ingest`, so
    // this does not introduce a cycle -- both already sit strictly below `cli`/`mcp-server`.
    implementation(project(":modules:extractors"))
    implementation(project(":modules:storage-sqlite"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlin.logging)
    // describe-modules (slice 15): its own LiteLLM client, deliberately separate from
    // SemanticExtractor's (modules/extractors) -- see ModuleDescriber.kt for why.
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.serialization)
    runtimeOnly(libs.logback)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    // Test-only: lets ModuleDescriberTest assert on the actual outgoing HTTP request body
    // (the no-raw-source-in-payload property) instead of trusting the code that builds it.
    // Same ktor version already pinned in gradle/libs.versions.toml; no alias added there.
    testImplementation("io.ktor:ktor-client-mock:3.0.3")
}
