#!/bin/sh
# Shared constants and helpers for the identifier-segment-vocabulary measurement rig. Sourced by
# every other script in this directory; never invoked directly.
#
# Every path an arm's numbers could depend on is named here once, so a later user of this rig on
# a different arm (or a different machine) cannot accidentally point two scripts at two
# different corpora, or two different Java/Gradle installs.
#
# Every machine-specific value below is a `${VAR:-default}` -- overridable from the environment,
# falling back to the exact recipe this run verified in this sandbox (see
# scripts/segvocab/README.md's "Reproducing a row" section for where that recipe came from).
# This follows the same resolve-from-environment shape as scripts/ci-reindex.sh's
# `$(dirname "${BASH_SOURCE[0]}")`, applied to Java/Gradle instead of the repo root, which this
# file already resolved relative to `$0` rather than a hardcoded path.
set -eu

# Repository root, resolved from wherever the caller invoked us -- never assumed to be cwd.
SEGVOCAB_REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd -P)

# Everything private to this run: the frozen corpus copy, build snapshots, retrieval result
# JSON, and this rig's own bookkeeping. Nothing here is tracked by git; nothing under
# $SEGVOCAB_SHARED_CORPUS is ever written by this rig (see prepare-corpus.sh and
# check-shared-corpus-unmodified.sh for the read-only guarantee and its proof).
SEGVOCAB_PRIVATE_ROOT="${SEGVOCAB_PRIVATE_ROOT:-/tmp/claude/segvocab-run}"
SEGVOCAB_CORPUS_ROOT="$SEGVOCAB_PRIVATE_ROOT/corpus"
SEGVOCAB_SNAPSHOTS_ROOT="$SEGVOCAB_PRIVATE_ROOT/snapshots"
SEGVOCAB_RESULTS_ROOT="$SEGVOCAB_PRIVATE_ROOT/results"
SEGVOCAB_SHARED_CORPUS="${SEGVOCAB_SHARED_CORPUS:-/tmp/claude/benchmark-corpus}"
SEGVOCAB_SHARED_CORPUS_BASELINE="$SEGVOCAB_PRIVATE_ROOT/shared-corpus-baseline.json"

# The only repo this rig's arms measure by default: ContextGraph's retrieval denominator is
# excalidraw's 9 questions (Keycloak's ContextGraph side is gated out; its ingest cost is a
# two-shot, hand-driven measurement, not something this rig's per-arm default needs to cover).
SEGVOCAB_REPO_ID="${SEGVOCAB_REPO_ID:-excalidraw}"

# Java/Gradle, overridable so this rig is not wired to one developer's machine. Defaults are the
# offline recipe this run verified works in its own sandbox: no `java` on PATH at all, and
# `~/.gradle` itself read-only (hence GRADLE_USER_HOME pointed at a writable scratch dir, with
# GRADLE_RO_DEP_CACHE -- read-only, never written -- still allowed to point at the real cache so
# nothing needs re-downloading). A different machine sets SEGVOCAB_JAVA_HOME/SEGVOCAB_GRADLE_BIN
# before sourcing this file, or before running any script here, and every default below yields.
SEGVOCAB_JAVA_HOME="${SEGVOCAB_JAVA_HOME:-$HOME/Library/Java/JavaVirtualMachines/jbr-17.0.8.1/Contents/Home}"
SEGVOCAB_GRADLE_BIN="${SEGVOCAB_GRADLE_BIN:-$HOME/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1/bin/gradle}"
SEGVOCAB_GRADLE_USER_HOME="${SEGVOCAB_GRADLE_USER_HOME:-$SEGVOCAB_PRIVATE_ROOT/gradle-home}"
SEGVOCAB_GRADLE_RO_DEP_CACHE="${GRADLE_RO_DEP_CACHE:-$HOME/.gradle/caches}"
SEGVOCAB_TMPDIR="${SEGVOCAB_TMPDIR:-/tmp/claude}"

# JAVA_TOOL_OPTIONS's java.io.tmpdir pin is load-bearing on macOS: the JBR JVM reads the
# per-process temp dir via confstr(_CS_DARWIN_USER_TEMP_DIR) rather than $TMPDIR, and a sandboxed
# run that denies writes there needs every forked JVM (Gradle test workers, this rig's own
# `java -cp` calls) pointed at a writable directory explicitly. Harmless, and a no-op in the
# common case, on a machine where the default temp dir is already writable. Kept as one function
# so every script invokes gradle identically.
#
# First argument is the project directory (gradle -p); everything after it is passed
# through as gradle arguments/tasks. Callers that don't need to point at a different
# source tree than this rig's own repo root should pass "$SEGVOCAB_REPO_ROOT" explicitly --
# no implicit default here, so it is always visible at the call site which tree is built.
segvocab_gradle() {
    proj=$1; shift
    JAVA_HOME="$SEGVOCAB_JAVA_HOME" \
    GRADLE_USER_HOME="$SEGVOCAB_GRADLE_USER_HOME" \
    GRADLE_RO_DEP_CACHE="$SEGVOCAB_GRADLE_RO_DEP_CACHE" \
    JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true -Djava.io.tmpdir=$SEGVOCAB_TMPDIR" \
    "$SEGVOCAB_GRADLE_BIN" \
        --offline --no-watch-fs -p "$proj" \
        -Dorg.gradle.jvmargs="-Djava.io.tmpdir=$SEGVOCAB_TMPDIR -Xmx3g" \
        -Dkotlin.compiler.execution.strategy=in-process \
        "$@"
}

# The JDK a snapshot's `java -cp ...` invocations run under -- the same one gradle used to
# compile it. Never resolved by bare `java`; a sandboxed run may have none on PATH at all.
SEGVOCAB_JAVA="$SEGVOCAB_JAVA_HOME/bin/java"

segvocab_log() {
    printf '[segvocab] %s\n' "$*" >&2
}

segvocab_die() {
    printf '[segvocab] FATAL: %s\n' "$*" >&2
    exit 1
}
