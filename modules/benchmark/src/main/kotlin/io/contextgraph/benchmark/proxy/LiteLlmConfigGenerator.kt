package io.contextgraph.benchmark.proxy

import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds the LiteLLM proxy's `config.yaml`: one `model_list` entry per judge model name, each
 * routed to OpenAI under the identical model string the judge client sends -- no separate
 * name-mapping table exists anywhere else in this module, so inventing one here would just be a
 * second place for the two to drift apart. `openai/<model>` is litellm's own provider-prefix
 * convention for routing that exact string to the OpenAI backend.
 *
 * Task 20: this pointed at Anthropic (`anthropic/<model>`, `ANTHROPIC_API_KEY`) until the
 * Anthropic key on this account came back invalid (401, confirmed directly) and the user switched
 * to a verified OpenAI key. The proxy's own install/health-check/start-stop lifecycle
 * ([LiteLlmProxyManager], [LiteLlmProxyLifecycle]) is unchanged by this -- only which provider it
 * looks at.
 *
 * The generated text never contains an API key value: [generate] has no parameter a key could
 * travel in on at all -- only [API_KEY_ENV_VAR]'s *name*, via `os.environ/...`, litellm's own
 * indirection syntax for its process to resolve from its own environment at request time. The
 * same structural guarantee [io.contextgraph.benchmark.judge.buildJudgeMessages] uses for AC-11.
 */
object LiteLlmConfigGenerator {
    const val API_KEY_ENV_VAR = "OPENAI_API_KEY"

    fun generate(models: List<String>): String {
        require(models.isNotEmpty()) { "At least one judge model is required to generate a LiteLLM config." }
        return buildString {
            appendLine("model_list:")
            for (model in models.distinct()) {
                appendLine("  - model_name: $model")
                appendLine("    litellm_params:")
                appendLine("      model: openai/$model")
                appendLine("      api_key: os.environ/$API_KEY_ENV_VAR")
            }
        }
    }

    fun writeTo(path: Path, models: List<String>): Path {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, generate(models))
        return path
    }
}
