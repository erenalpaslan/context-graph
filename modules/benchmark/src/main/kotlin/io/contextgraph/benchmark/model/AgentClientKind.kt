package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * AC-18a: what kind of [io.contextgraph.benchmark.runner.AgentClient] produced a
 * [BenchmarkRun]'s agent runs -- the field that determines whether the numbers in that run are a
 * measurement or a pipeline proof.
 *
 * - [REAL] -- a real coding agent
 *   ([io.contextgraph.benchmark.runner.AnthropicMessagesClient]) actually ran each turn.
 * - [SYNTHETIC] -- a scripted stand-in produced the outcome (e.g. a `smoke` proof that exercises
 *   corpus prep, the MCP tool bridge, judging, and reporting end to end without spending a real
 *   agent turn). The token counts, costs, and accuracy scores under [SYNTHETIC] prove the
 *   pipeline runs; they are not evidence ContextGraph helps or hurts on any question.
 *
 * [BenchmarkRun.agentClientKind] is nullable with no default value smuggled in as "REAL" --
 * result JSON written before this field existed must decode as "not recorded", genuinely
 * distinct from "recorded as real". See `BenchmarksReportGenerator`, which only suppresses the
 * `BENCHMARKS.md` synthetic-run warning for an explicit [REAL], not for a null/unrecorded value.
 */
@Serializable
enum class AgentClientKind {
    REAL,
    SYNTHETIC
}
