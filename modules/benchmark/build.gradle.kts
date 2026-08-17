plugins {
    application
}

application {
    mainClass.set("io.contextgraph.benchmark.cli.MainKt")
}

// NOTE for slices 02-06: this file is shared territory. Front-loaded below
// with every dependency the corpus/questions/runner/judge slices are likely
// to need, precisely so five people don't all need to edit this same file in
// the same round. If something you need genuinely isn't here, add exactly
// the one line you need rather than restructuring — see
// modules/benchmark/README.md for the package/territory map.
dependencies {
    // Internal modules: corpus prep (02) indexes repos through the same
    // pipeline the CLI uses; the agent runner (04) needs the MCP server to
    // hand to the WITH_TOOLS arm.
    implementation(project(":modules:core"))
    implementation(project(":modules:ingest"))
    implementation(project(":modules:extractors"))
    implementation(project(":modules:graph"))
    implementation(project(":modules:storage-sqlite"))
    implementation(project(":modules:query"))
    implementation(project(":modules:mcp-server"))
    implementation(project(":modules:cli"))

    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.clikt)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback)

    // HTTP client stack, for whichever slice ends up calling a model or a
    // LiteLLM proxy directly (agent runner / judge — 04, 05), consistent
    // with the ktor stack already used elsewhere in the project.
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.serialization)

    // YAML option for question set data files (03) alongside JSON, which
    // kotlinx.serialization already covers.
    implementation(libs.snakeyaml)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
}
