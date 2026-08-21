plugins {
    application
}

application {
    mainClass.set("io.contextgraph.benchmark.cli.MainKt")
}

// Corpus preparation (slice 02, AC-1/AC-1a/AC-2) as its own JavaExec task rather than
// changing `application.mainClass`: it must stay reachable without disturbing
// `./gradlew :modules:benchmark:run` (which still writes the model-only smoke/full JSON,
// see BenchmarkCliTest), and it must NOT be wired into `build`/`check` (AC-22) — a task
// registered with no dependency edge into either lifecycle only runs when named explicitly.
// Usage: ./gradlew :modules:benchmark:prepareCorpus -PcorpusArgs="--corpus-root .benchmark-corpus"
// A relative --corpus-root (including the default above) always resolves against the
// repository root, not this task's working directory (modules/benchmark) — see
// io.contextgraph.benchmark.cli.RepoRoot. Pass an absolute path to opt out of that. Gradle
// already knows the repo root with certainty at configuration time, so this task hands it
// over explicitly via a system property rather than making the command re-derive it with a
// directory walk (which could anchor to the wrong tree if an unrelated settings.gradle.kts
// ever sat between this task's cwd and the true root).
tasks.register<JavaExec>("prepareCorpus") {
    group = "benchmark"
    description = "Clone-or-verify the pinned corpus repos' two working copies and index the " +
        "WITH copy (AC-1, AC-1a, AC-2). Never part of build/check (AC-22)."
    mainClass.set("io.contextgraph.benchmark.cli.PrepareCorpusCliKt")
    classpath = sourceSets.main.get().runtimeClasspath
    // Property name must match io.contextgraph.benchmark.cli.RepoRoot.REPO_ROOT_PROPERTY —
    // duplicated as a literal because this build script can't import that runtime class.
    systemProperty("contextgraph.benchmark.repoRoot", rootProject.projectDir.absolutePath)
    args = (project.findProperty("corpusArgs") as String?)
        ?.split(" ")
        ?.filter { it.isNotBlank() }
        ?: emptyList()
}

// Kappa validation (task 14, AC-14) as its own JavaExec task, same reasoning as prepareCorpus
// above: a one-off confidence check on the judge, invoked against an already-produced result
// JSON, never part of an ordinary benchmark run and never wired into build/check (AC-22).
// Usage: ./gradlew :modules:benchmark:validateKappa -PkappaArgs="--result results/run-....json --secondary-judge-model claude-sonnet-5"
tasks.register<JavaExec>("validateKappa") {
    group = "benchmark"
    description = "Re-score a ~20% subsample of an existing result's judged runs with a " +
        "second judge model and fold agreement/kappa into its BENCHMARKS.md (AC-14). Never " +
        "part of build/check (AC-22)."
    mainClass.set("io.contextgraph.benchmark.cli.KappaValidationCliKt")
    classpath = sourceSets.main.get().runtimeClasspath
    args = (project.findProperty("kappaArgs") as String?)
        ?.split(" ")
        ?.filter { it.isNotBlank() }
        ?: emptyList()
}

// Retrieval axis (task 15, AC-23..AC-26) as its own JavaExec task, same reasoning as
// prepareCorpus/validateKappa above: LLM-free and deterministic, runs on its own schedule
// against an already-prepared corpus, never part of build/check (AC-22).
// Usage: ./gradlew :modules:benchmark:runRetrieval -PretrievalArgs="--rg-path /opt/homebrew/bin/rg"
tasks.register<JavaExec>("regenerateReport") {
    group = "verification"
    description = "Rewrite BENCHMARKS.md from an existing result JSON, without re-running anything"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.contextgraph.benchmark.cli.RegenerateReportCliKt")
    if (project.hasProperty("args")) args((project.property("args") as String).split(" "))
}

tasks.register<JavaExec>("runRetrieval") {
    group = "benchmark"
    description = "Score ContextGraph's QueryEngine.buildContext against a ripgrep baseline " +
        "over the gold question set (AC-23..AC-26). LLM-free, deterministic. Requires an " +
        "already-prepared corpus and `rg` on PATH. Never part of build/check (AC-22)."
    mainClass.set("io.contextgraph.benchmark.cli.RetrievalCliKt")
    classpath = sourceSets.main.get().runtimeClasspath
    systemProperty("contextgraph.benchmark.repoRoot", rootProject.projectDir.absolutePath)
    args = (project.findProperty("retrievalArgs") as String?)
        ?.split(" ")
        ?.filter { it.isNotBlank() }
        ?: emptyList()
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
    // Same ktor version already pinned in gradle/libs.versions.toml; no alias added there
    // (matches modules/ingest's LiteLlmModuleDescriberTest, same pattern here for LlmJudgeClient).
    testImplementation("io.ktor:ktor-client-mock:3.0.3")
}
