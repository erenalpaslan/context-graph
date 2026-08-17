package io.contextgraph.benchmark.model

import kotlinx.serialization.Serializable

/**
 * AC-5 category label. Target distribution, per repo over ~8 questions:
 * ~60% [GRAPH_HEAVY], ~25% [NEUTRAL], ~15% [NEGATIVE_CONTROL]. Enforcing and
 * reporting the actual distribution against that target is slice 03's job;
 * this is just the label.
 */
@Serializable
enum class QuestionCategory {
    GRAPH_HEAVY,
    NEUTRAL,
    NEGATIVE_CONTROL
}
