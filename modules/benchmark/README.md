# `:modules:benchmark`

Measures the agent-facing benefit of ContextGraph's MCP tools: the same
question, same model, run once with ContextGraph's MCP server available and
once without, comparing token/tool-call/time/cost and answer accuracy. See
`agent-team/specs/benchmark-suite/spec.md` for the full spec and acceptance
criteria (AC-1..AC-22, AC-10a).

This module is not depended on by anything else in the project — it is a
leaf, on purpose (see the spec's non-goals: no CI gate, no regression
threshold blocks a build).

## Why won't this build?

On a developer machine with network access, `./gradlew :modules:benchmark:build`
just works and you can skip this section.

In a sandboxed or offline environment it fails in six distinct ways, none of
which names its real cause in the error message. Three autonomous runs each
rediscovered the first four from scratch before they were written down here; the
last two only appear once the first four are fixed and the test task finally runs.
Each fix is a local file or environment change — **none of them needs a network
host beyond `github.com`, and none needs a sandbox policy change.**

| Symptom | Cause | Fix |
|---|---|---|
| `Could not initialize native services` → `Failed to load native library 'libnative-platform.dylib'`, from a `FileNotFoundException` on `…/libnative-platform.dylib.lock (Operation not permitted)` | Gradle writes lock files and native scratch into `GRADLE_USER_HOME`, and `~/.gradle` is not writable here. Reads are fine; only writes are denied. | Point `GRADLE_USER_HOME` at a writable scratch directory. |
| Dependency resolution fails under `--offline` | The scratch `GRADLE_USER_HOME` from the previous row starts empty, so no dependency is cached *in it*. | `GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches` lets Gradle **read** the host's existing ~749 MB dependency cache without writing to it — this is what makes `--offline` resolve, so Maven Central is never contacted. |
| `./gradlew` → `Could not find or load main class org.gradle.wrapper.GradleWrapperMain` | `gradle/wrapper/gradle-wrapper.jar` is **untracked**: `.gitignore`'s `*.jar` (line 11) is the *last* matching pattern and so beats the `!gradle/wrapper/gradle-wrapper.jar` negation on line 4. Git applies the last match, so no clone and no worktree ever receives the jar. Confirm with `git check-ignore -v gradle/wrapper/gradle-wrapper.jar`. | Invoke the already-unpacked Gradle distribution directly instead of the wrapper. It is the exact version `gradle-wrapper.properties` pins, so this is the same Gradle the wrapper would have fetched. |
| `compileTreeSitterGrammars` fails fetching from `codeload.github.com` | `:modules:benchmark` → `ingest` → `extractors` → `tree-sitter`, and `tree-sitter`'s `processResources` depends on `compileTreeSitterGrammars`, which downloads eight codeload tarballs covering all nine pinned grammar specs. `codeload.github.com` is a different host from `github.com` and is not reachable here. | Seed the grammar cache by copying `modules/tree-sitter/build/tree-sitter-src/` (and `tree-sitter-download/`) from an existing checkout — see below. |
| `Kotest > initializationError` with `FileSystemException: /var/folders/…/T/…: Operation not permitted` | Gradle forks a **separate JVM per test task**, whose `java.io.tmpdir` defaults to the macOS per-user temp dir that the sandbox denies. `-Dorg.gradle.jvmargs` configures the Gradle process, **not** its test workers, so it never reaches them. Setting `TMPDIR` does not help either: macOS JVMs read `_CS_DARWIN_USER_TEMP_DIR` and ignore `TMPDIR`. | Set `java.io.tmpdir` on the `Test` task itself — see the init script below. |
| `:modules:cli:test` → 7 failures in `FreshnessTest`/`DescribeModulesCommandTest`, each a bare `expected:<0> but was:<1>` | **Not fixable from here, and not a regression — expect these seven.** Both classes fork a *second* JVM (`ProcessBuilder`, running `MainKt` on the test classpath) because only a real process boundary proves a file was or wasn't written. The row above fixes the test *worker*, but a system property is not inherited across `ProcessBuilder`, so the grandchild is back on the denied temp dir — and `sqlite-jdbc` unpacks its native library there before any query runs. The real error is hidden inside the subprocess's captured output: `org.sqlite.NativeLibraryNotFoundException … os.arch=aarch64`. Every cli test that does *not* fork, and every forked test that stays read-only (`ReadOnlyCommandsDoNotCreateBaselineTest`), passes. | Confirm rather than chase: `JAVA_OPTS=-Djava.io.tmpdir=$SCRATCH/clitmp modules/cli/build/install/cli/bin/cli refresh` in a scratch project exits 0. A real fix means passing `-Djava.io.tmpdir` down in `runCli`, which is a product change, not an environment one. |

### Seeding the tree-sitter grammar cache

`compileTreeSitterGrammars` skips the download for any grammar whose
`build/tree-sitter-src/<owner>_<repo>@<sha>/.extracted` marker already exists, so
copying that directory from a checkout that has built before is what removes the
network requirement:

```bash
MAIN=/path/to/an/existing/context-graph/modules/tree-sitter/build
WT=$PWD/modules/tree-sitter/build          # this worktree
rsync -a "$MAIN/tree-sitter-download/"        "$WT/tree-sitter-download/"
rsync -a "$MAIN/tree-sitter-src/"             "$WT/tree-sitter-src/"
rsync -a "$MAIN/generated/tree-sitter-natives/" "$WT/generated/tree-sitter-natives/"
```

Eight archives cover all nine grammar specs — `typescript` and `tsx` are two
grammars built from one repo and commit.

Copying `generated/tree-sitter-natives/` is worth doing but **do not rely on the
`.dylib`s surviving**: that directory is a registered task output, so Gradle's
stale-output cleanup deletes its contents on the first build in a fresh worktree.
That is harmless — `cc` recompiles all nine libraries from the seeded sources in
a few seconds, still without opening a socket. `tree-sitter-src/` is the
load-bearing half of the copy.

#### Seeding a grammar nothing on the machine has ever fetched

The copy above only works for grammars some checkout has already downloaded. Add
a *new* grammar and there is nothing to copy from, which is the one case that
looks like it needs `codeload.github.com`. It does not: `github.com` is a
different host, is reachable, and serves the same commit over `git`. Clone at the
pinned SHA and repackage it into the layout the download step expects — a single
top-level `<repo>-<sha>/` directory, named `<owner>_<repo>@<sha>.tar.gz`:

```bash
SHA=3c3775faa968158a8b4ac190a7fda867fd5fb748     # the pin in build.gradle.kts
NAME=tree-sitter_tree-sitter-go@$SHA
TOP=tree-sitter-go-$SHA
git clone --depth 1 --branch v0.23.4 https://github.com/tree-sitter/tree-sitter-go "$TMP/$TOP"
git -C "$TMP/$TOP" rev-parse HEAD                # must print $SHA
tar -C "$TMP" --exclude .git -czf \
  "$PWD/modules/tree-sitter/build/tree-sitter-download/$NAME.tar.gz" "$TOP"
```

`compileTreeSitterGrammars` extracts a cached tarball without opening a socket,
so this is the whole fix. **Verify the commit's ABI before pinning it**: read
`#define LANGUAGE_VERSION` at the top of the clone's checked-in `src/parser.c`.
ktreesitter 0.24.1's runtime accepts 14 and rejects 15, which is why `python` and
`go` are both pinned a minor version behind their newest tag.

### The recipe

Substitute your own absolute paths for `SCRATCH` (any writable directory) and
`WT` (the worktree being built).

```bash
SCRATCH=/tmp/cg-build            # any writable dir; holds GRADLE_USER_HOME + temp
WT=$PWD
mkdir -p "$SCRATCH/gradle-home/init.d"

# One-time: give each forked test-worker JVM a writable temp dir. Gradle applies every
# script in <GRADLE_USER_HOME>/init.d/ automatically, so this needs no flag at the call
# site and is picked up by anyone sharing this GRADLE_USER_HOME.
cat > "$SCRATCH/gradle-home/init.d/sandbox-test-tmpdir.gradle" <<'EOF'
def sandboxTmp = System.getenv('CONTEXTGRAPH_TEST_TMPDIR')
        ?: new File(gradle.gradleUserHomeDir, 'test-tmp').absolutePath
new File(sandboxTmp).mkdirs()
allprojects {
    tasks.withType(Test).configureEach {
        systemProperty 'java.io.tmpdir', sandboxTmp
    }
}
EOF

JAVA_HOME=/Users/<you>/Library/Java/JavaVirtualMachines/jbr-17.0.8.1/Contents/Home \
GRADLE_USER_HOME="$SCRATCH/gradle-home" \
GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
~/.gradle/wrapper/dists/gradle-8.11.1-bin/*/gradle-8.11.1/bin/gradle \
  --offline --no-watch-fs --console=plain -p "$WT" \
  -Dorg.gradle.jvmargs="-Djava.io.tmpdir=$SCRATCH -Xmx3g" \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Dkotlin.daemon.jvmargs=-Djava.io.tmpdir=$SCRATCH \
  :modules:benchmark:build
```

Why each piece, one line each:

- **`JAVA_HOME` set explicitly** — there is no `java` on `PATH` in this
  environment and `/usr/libexec/java_home` fails outright, so Gradle cannot find
  a JDK on its own. JBR **17** specifically, because `build.gradle.kts`'s
  `jvmToolchain` and `compileTreeSitterGrammars`' `javaToolchains.launcherFor`
  both ask for 17.
- **`GRADLE_USER_HOME` in a writable directory** — the default `~/.gradle` is
  read-only here, and Gradle cannot even start without writing its native-platform
  lock files.
- **`GRADLE_RO_DEP_CACHE`** — supplies the dependencies that the now-empty
  `GRADLE_USER_HOME` lacks, read-only, which is what lets `--offline` succeed
  instead of reaching for Maven Central.
- **Calling the cached distribution directly, not `./gradlew`** — the wrapper jar
  is untracked (see the table), so `./gradlew` cannot work in any fresh clone.
- **The `init.d` script** — gives the forked test-worker JVMs a writable
  `java.io.tmpdir`, which `-Dorg.gradle.jvmargs` cannot reach. It defaults to
  `<GRADLE_USER_HOME>/test-tmp` and needs no flag at the call site, so it applies
  to every build sharing this `GRADLE_USER_HOME`.
- **Seeding the grammar cache** — replaces the nine `codeload.github.com`
  downloads with a local file copy, since that host is unreachable here.
- **`--offline`** — turns any accidental network access into an immediate, legible
  failure rather than a long hang.
- **`-Dkotlin.compiler.execution.strategy=in-process`** — the Kotlin compile daemon
  cannot start in this sandbox; compiling in-process makes that the intended path.

Two messages during a successful build are **benign and are not failures**:

- `Could not connect to Kotlin compile daemon … Using fallback strategy: Compile
  without Kotlin daemon` — expected, given the in-process strategy above.
- `jansi-…jnilib.lck (Operation not permitted)` under `/var/folders` — a warning
  from the console library; it does not affect the build.

Verified on 2026-08-24, after the four-way retrieval work landed:
`:modules:benchmark:build` → BUILD SUCCESSFUL, and `:modules:benchmark:test` →
0 failures, exactly 1 skipped by design (`LiveSmokeOrchestrationTest`, see
AC-22 below), and every other test passes. A fully warm rebuild takes under
one second.

**This file does not print a test count.** It printed 426, then 472, and by
the time either number was read here it was already wrong — the total rises
whenever anyone adds a test, and any figure written down is a snapshot, not a
contract. Read the current total yourself instead: `./gradlew
:modules:benchmark:test` if you have network access, as the top of this
section describes — or the recipe above with `:modules:benchmark:test` as
the final target in place of `:modules:benchmark:build`, if you don't. What
this file holds itself to is the rest of the sentence: 0 failing, and
exactly one skip, which is a deliberate one.

## Package map

Slice 01 (this skeleton) owns `model` and `cli`. Everything else below is
**reserved territory** for the slice that owns it — put new code for that
concern under that package so two slices working in this worktree at the
same time don't collide on the same files.

| Package | Owner (slice) | Concern |
|---|---|---|
| `io.contextgraph.benchmark.model` | 01 | The whole-run domain model and its JSON serialization (`BenchmarkRun` and everything it's built from). This is the contract — read it, don't fork it. Changes here ripple into every other package. |
| `io.contextgraph.benchmark.cli` | 01 (skeleton), 12 (wiring) | The `--profile smoke\|full` entry point. Slice 01 leaves it deliberately unwired; slice 12 is the only slice that should add orchestration calls here. |
| `io.contextgraph.benchmark.corpus` | 02 | Pinned repo clone/verify, `litellm.enabled=false` indexing, ingest metrics → `IngestRecord`. |
| `io.contextgraph.benchmark.questions` | 03 | Question set data format (YAML/JSON), loader, validator (evidence format, 3-6 facts, category distribution, duplicate ids) → `Question`/`GoldFact`. |
| `io.contextgraph.benchmark.runner` | 04 | Single (question, arm) agent run: WITH_TOOLS vs WITHOUT_TOOLS, contamination guard (sanitized PATH + PreToolUse hook), tool-call ceiling, metric collection → `AgentRunRecord`. |
| `io.contextgraph.benchmark.judge` | 05 | Blind scoring of an answer against gold facts, kappa validation mode → `JudgeScore`/`FactScore`. |
| `io.contextgraph.benchmark.stats` | 06 | Aggregation across repeats (median + min/max), contamination/ceiling exclusion accounting, break-even calculation. Pure functions, no I/O. |
| `io.contextgraph.benchmark.report` | 07 | `BENCHMARKS.md` generation from slice 06's summary. Formatting only — no new calculations. |
| (question data files) | 08-11 | Repo-specific question sets — one data file per repo (Excalidraw, gin, cal.com, Keycloak), *not* Kotlin source. Don't put these under `questions`' test fixtures; that's slice 03's test data, not the real gold sets. |
| `io.contextgraph.benchmark.orchestrator` (new) | 12 | Ties 02-07 together behind the two profiles. Slice 01 does not create this package — it's slice 12's to add. |

## Model contract (owned by slice 01)

`BenchmarkRun` is the versioned root (`schemaVersion` field, see
`BenchmarkRun.SCHEMA_VERSION`) that every other slice writes fields onto and
reads fields from:

- `CorpusRepo` — pinned repo + tag/SHA (02 writes).
- `Question` / `GoldFact` / `Evidence` — question set (03 writes; `Evidence`
  is a mandatory `file:line` value, unrepresentable without one — see
  `Evidence.parse`).
- `IngestRecord` — per-repo indexing cost, kept separate from query metrics
  (02 writes).
- `AgentRunRecord` — one (question, arm, repeat) measurement (04 writes).
- `JudgeScore` / `FactScore` — blind scoring output, one or more per run,
  keyed by `judgeModel` for the kappa validation mode (05 writes).
- `BenchmarkConfig` / `ModelConfig` — tool-call ceiling, repeats per arm,
  agent/judge model. Configuration, not constants: profiles vary these,
  nothing in the suite should hard-code 40, 4, or a model name directly.

If you (a downstream slice) find you need a field that isn't here: add it to
the relevant `model` class rather than smuggling the data through a side
channel — a field missing from this contract is a six-file change later,
per the task 01 brief. Keep the addition minimal and additive (new field
with a default, so old result JSON still round-trips).

## Running

```bash
./gradlew :modules:benchmark:build              # compiles and runs unit tests only
./gradlew :modules:benchmark:run --args="--profile smoke"   # writes an (currently empty) result JSON
```

If either of those fails — or if `./gradlew` itself will not start — see
[Why won't this build?](#why-wont-this-build) above rather than debugging it
fresh; all five known causes are environmental and already have fixes.

`./gradlew build` / `./gradlew check` at the repo root never execute the
benchmark (AC-22) — the `application` plugin's `run` task is not wired into
either lifecycle.
