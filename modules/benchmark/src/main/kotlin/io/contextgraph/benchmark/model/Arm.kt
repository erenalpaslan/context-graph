package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * Which arm an agent run belongs to. Per AC-7, tool access is the *only*
 * intentional difference between arms — model, system prompt, question text
 * and tool-call ceiling are identical.
 */
@Serializable
enum class Arm {
    WITH_TOOLS,
    WITHOUT_TOOLS
}
