package io.contextgraph.ingest.describe

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** [ModuleVectorCodec] is the "stored as blobs" encoding the slice's notes call for: a round trip must reproduce every float exactly. */
class ModuleVectorCodecTest : FunSpec({

    test("encode then decode reproduces the original vector exactly") {
        val vector = FloatArray(ModuleEmbeddingModel.DIMENSION) { i -> (i - 768) / 1000f }

        val decoded = ModuleVectorCodec.decode(ModuleVectorCodec.encode(vector))

        decoded shouldBe vector
    }

    test("an empty vector round-trips to an empty vector") {
        ModuleVectorCodec.decode(ModuleVectorCodec.encode(FloatArray(0))) shouldBe FloatArray(0)
    }
})
