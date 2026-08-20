package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * Fixed model choices (AC-12), recorded on every result so a reader can see what produced it.
 *
 * The judge defaulted to `claude-opus-5` originally, precisely so it would differ from the agent
 * and close the "the judge graded its own model" objection. The first real run hit a hard
 * `rate_limit_error` on Opus 5 -- its rate-limit pool is separate from the Opus 4.x one, and this
 * account has almost no headroom there -- so the default moved to the agent's own model
 * (`claude-sonnet-5` for both).
 *
 * Task 20: the Anthropic key on this account then came back invalid outright (401, confirmed
 * directly against the API, not just a rate limit) -- so both fields moved a second time, to
 * `gpt-4.1-nano` (the cheapest model in the verified-working OpenAI account, key confirmed via a
 * 200 from `/v1/models`). The reasoning above about a shared agent/judge model still applies
 * unchanged: the benchmark's headline is a *delta* between two arms of the same agent model, a
 * judge that shares that model biases both arms equally so the delta survives, and AC-14's kappa
 * validation remains the mitigation for the weakened absolute-accuracy optics. A known,
 * deliberately unresolved risk from this move: a nano-class model may not use tools reliably
 * enough in an agentic loop, which could show up as an empty WITH_TOOLS/WITHOUT_TOOLS arm that
 * looks like "ContextGraph doesn't help" when the real cause is the model's own tool-use
 * competence -- acceptable for `smoke`, to be revisited before a `full` run's numbers are cited.
 *
 * That risk then materialised, in both roles, and is why the default is no longer `nano`. The
 * first end-to-end `smoke` on real OpenAI keys produced real tokens and readable answers -- and
 * measurements worth nothing:
 *
 *  - As **agent**: one tool call per WITH_TOOLS run and *zero* file reads in either arm. The
 *    control arm never explored the repo at all; one answer described Fiber, a different Go
 *    framework, and another said it could not access the source files.
 *  - As **judge**: it scored all six runs 1.0, a perfect 6/6 on gold facts -- including the
 *    answer about the wrong framework and the one that admitted it had read nothing. A judge
 *    that cannot fail an answer is not measuring anything.
 *
 * The delta between arms was therefore zero, which reads as "ContextGraph makes no difference"
 * when the real finding is "neither arm was measured". Both fields moved to `gpt-4.1`.
 *
 * The first `full` run then failed on a limit neither model choice had exposed before: this
 * account is capped at **30,000 tokens per minute on `gpt-4.1`**, read directly off
 * `x-ratelimit-limit-tokens`, and an agentic run accumulates far more than that. 13 of the first
 * 18 runs died with `429 ... Request too large ... Limit 30000, Requested 31959` -- a single
 * request larger than the whole per-minute budget, so no retry policy can rescue it. Even the
 * runs that fit would be throttled to roughly one every three minutes (a smoke run on `gin`, the
 * smallest repo in the corpus, measured a median of 90k and a peak of 175k cumulative input
 * tokens once it reached the 40-tool-call ceiling), which puts a 200-run profile near ten hours.
 *
 * The same header reports **200,000 TPM on `gpt-4.1-mini`** for this account -- 6.7x the
 * headroom, on the identical key and tier. So the load moved to `mini` and the *judge* stayed on
 * `gpt-4.1`, which is a strictly better split than the one it replaces:
 *
 *  - The agent is what accumulates context across an agentic loop; the judge sees one answer and
 *    its gold facts in a single call, comfortably under 30k, and there are far fewer such calls.
 *  - It restores what AC-12 originally wanted and the Opus-5 rate limit took away: a judge on a
 *    *different, stronger* model than the agent, which closes the "the model graded its own
 *    answer" objection without needing AC-14's kappa run to carry that weight alone.
 *
 * `mini` is not `nano`, and the failure above was specifically a nano-class one; the risk that it
 * repeats is nonetheless real and is why the model change is validated by a `smoke` run measuring
 * tool calls and file reads per arm before any `full` numbers are cited.
 *
 * Both fields are configuration, not constants: pass a different judge here the moment quota
 * allows, and nothing else needs to change.
 */
@Serializable
data class ModelConfig(
    val agentModel: String = "gpt-4.1-mini",
    val judgeModel: String = "gpt-4.1"
)
