#!/bin/sh
# Shared constants and helpers for the identifier-segment-vocabulary measurement rig
# (agent-team/tasks/02-measurement-rig.md). Sourced by every other script in this directory;
# never invoked directly.
#
# Every path an arm's numbers could depend on is named here once, so a later slice reusing
# this rig on a different arm cannot accidentally point two scripts at two different corpora.
set -eu

# Repository root, resolved from wherever the caller invoked us -- never assumed to be cwd.
SEGVOCAB_REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd -P)

# Everything private to this run: the frozen corpus copy, build snapshots, retrieval result
# JSON, and this rig's own bookkeeping. Nothing here is tracked by git; nothing under
# /tmp/claude/benchmark-corpus is ever written by this rig (D15, hard prohibition 4).
SEGVOCAB_PRIVATE_ROOT="/tmp/claude/segvocab-run"
SEGVOCAB_CORPUS_ROOT="$SEGVOCAB_PRIVATE_ROOT/corpus"
SEGVOCAB_SNAPSHOTS_ROOT="$SEGVOCAB_PRIVATE_ROOT/snapshots"
SEGVOCAB_RESULTS_ROOT="$SEGVOCAB_PRIVATE_ROOT/results"
SEGVOCAB_SHARED_CORPUS="/tmp/claude/benchmark-corpus"
SEGVOCAB_SHARED_CORPUS_BASELINE="$SEGVOCAB_PRIVATE_ROOT/shared-corpus-baseline.json"

# The only repo this rig's arms measure (D1/D2/D5: ContextGraph's retrieval denominator is
# excalidraw's 9 questions; Keycloak's ContextGraph side is gated out and its cost is a
# two-shot, not a per-arm, measurement -- slice 03's business, not this rig's default).
SEGVOCAB_REPO_ID="excalidraw"

# The exact offline Gradle recipe verified live in this run's capabilities.json
# (gradleInSandbox). JAVA_TOOL_OPTIONS is load-bearing -- see that file's
# denialsReproducedHere for why forked test-worker JVMs need java.io.tmpdir pinned
# explicitly on macOS. Kept as one function so every script invokes gradle identically.
#
# First argument is the project directory (gradle -p); everything after it is passed
# through as gradle arguments/tasks. Callers that don't need to point at a different
# source tree than this rig's own repo root should pass "$SEGVOCAB_REPO_ROOT" explicitly --
# no implicit default here, so it is always visible at the call site which tree is built.
segvocab_gradle() {
    proj=$1; shift
    JAVA_HOME=/Users/erenalpaslan/Library/Java/JavaVirtualMachines/jbr-17.0.8.1/Contents/Home \
    GRADLE_USER_HOME="$SEGVOCAB_PRIVATE_ROOT/gradle-home" \
    GRADLE_RO_DEP_CACHE="$HOME/.gradle/caches" \
    JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true -Djava.io.tmpdir=/tmp/claude" \
    /Users/erenalpaslan/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1/bin/gradle \
        --offline --no-watch-fs -p "$proj" \
        -Dorg.gradle.jvmargs="-Djava.io.tmpdir=/tmp/claude -Xmx3g" \
        -Dkotlin.compiler.execution.strategy=in-process \
        "$@"
}

# The JDK a snapshot's `java -cp ...` invocations run under -- the same one gradle used to
# compile it. Never resolved by bare `java`; there is none on PATH (capabilities.json, toolchain).
SEGVOCAB_JAVA="/Users/erenalpaslan/Library/Java/JavaVirtualMachines/jbr-17.0.8.1/Contents/Home/bin/java"

segvocab_log() {
    printf '[segvocab] %s\n' "$*" >&2
}

segvocab_die() {
    printf '[segvocab] FATAL: %s\n' "$*" >&2
    exit 1
}
