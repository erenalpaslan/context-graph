package io.contextgraph.benchmark.runner

import io.contextgraph.benchmark.model.Arm
import io.contextgraph.benchmark.model.BenchmarkConfig
import io.contextgraph.benchmark.model.ModelConfig
import io.contextgraph.benchmark.model.Question
import io.contextgraph.benchmark.model.QuestionCategory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files

private fun question(id: String = "q1") =
    Question(id = id, repoId = "repo1", text = "What does the auth module do?", category = QuestionCategory.GRAPH_HEAVY, goldFacts = emptyList())

private class FakeMcpToolBridge(
    private val toolsList: List<ToolDefinition>,
    private val invoker: (String, String) -> String = { name, _ -> "unhandled:$name" }
) : McpToolBridge {
    override fun tools() = toolsList
    override fun invoke(name: String, inputJson: String) = invoker(name, inputJson)
    override fun close() {}
}

private class FakeAgentClient(private val behavior: (AgentRunContext) -> AgentClientOutcome) : AgentClient {
    var lastContext: AgentRunContext? = null
    var invocationCount = 0

    override fun run(context: AgentRunContext): AgentClientOutcome {
        lastContext = context
        invocationCount++
        return behavior(context)
    }
}

private fun outcome(
    toolCallCount: Int = 3,
    fileReadCount: Int = 1,
    inputTokens: Long = 1000,
    outputTokens: Long = 200,
    finalAnswer: String = "the answer"
) = AgentClientOutcome(
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    toolCallCount = toolCallCount,
    fileReadCount = fileReadCount,
    costUsd = 0.01,
    finalAnswer = finalAnswer
)

class AgentRunnerTest : FunSpec({

    val config = BenchmarkConfig(toolCallCeiling = 40, repeatsPerArm = 4, models = ModelConfig())

    test("AC-7: the two arms differ only in arm, workingDir, tools -- not model, prompt, question, or ceiling") {
        val indexedDir = Files.createTempDirectory("with-tools")
        Files.createDirectory(indexedDir.resolve(".contextgraph")) // legitimately indexed
        val cleanDir = Files.createTempDirectory("without-tools")

        val bridge = FakeMcpToolBridge(listOf(ToolDefinition("contextgraph_explore", "desc", "{}")))
        val client = FakeAgentClient { outcome() }
        val runner = AgentRunner(client, mcpToolBridgeFactory = { bridge })

        val q = question()
        runner.run(q, Arm.WITH_TOOLS, indexedDir, repeatIndex = 0, config = config)
        val withContext = client.lastContext!!

        runner.run(q, Arm.WITHOUT_TOOLS, cleanDir, repeatIndex = 0, config = config)
        val withoutContext = client.lastContext!!

        withContext.model shouldBe withoutContext.model
        withContext.systemPrompt shouldBe withoutContext.systemPrompt
        withContext.question shouldBe withoutContext.question
        withContext.toolCallCeiling shouldBe withoutContext.toolCallCeiling
        withContext.arm shouldBe Arm.WITH_TOOLS
        withoutContext.arm shouldBe Arm.WITHOUT_TOOLS
    }

    test("WITH_TOOLS arm is offered the ContextGraph tool; WITHOUT_TOOLS is offered none") {
        val indexedDir = Files.createTempDirectory("with-tools-2")
        Files.createDirectory(indexedDir.resolve(".contextgraph"))
        val cleanDir = Files.createTempDirectory("without-tools-2")

        val bridge = FakeMcpToolBridge(listOf(ToolDefinition("contextgraph_explore", "desc", "{}")))
        val client = FakeAgentClient { outcome() }
        val runner = AgentRunner(client, mcpToolBridgeFactory = { bridge })

        runner.run(question(), Arm.WITH_TOOLS, indexedDir, repeatIndex = 0, config = config)
        client.lastContext!!.extraTools.map { it.name } shouldBe listOf("contextgraph_explore")

        runner.run(question(), Arm.WITHOUT_TOOLS, cleanDir, repeatIndex = 0, config = config)
        client.lastContext!!.extraTools.shouldBeEmpty()
    }

    test("AC-7a: a WITHOUT_TOOLS run refuses to start when the working copy carries an artefact, and the agent client is never invoked") {
        val dirtyDir = Files.createTempDirectory("dirty-without")
        Files.createDirectory(dirtyDir.resolve(".contextgraph"))

        val client = FakeAgentClient { outcome() }
        val runner = AgentRunner(client)

        shouldThrow<ContaminatedWorkingCopyException> {
            runner.run(question(), Arm.WITHOUT_TOOLS, dirtyDir, repeatIndex = 0, config = config)
        }
        client.invocationCount shouldBe 0
    }

    test("a clean WITHOUT_TOOLS working copy is allowed to start") {
        val cleanDir = Files.createTempDirectory("clean-without")
        val client = FakeAgentClient { outcome() }
        val runner = AgentRunner(client)

        val record = runner.run(question(), Arm.WITHOUT_TOOLS, cleanDir, repeatIndex = 0, config = config)

        record.contaminated shouldBe false
        client.invocationCount shouldBe 1
    }

    test("AC-9: a file appearing in the WITHOUT_TOOLS working copy during the run marks the record contaminated") {
        val cleanDir = Files.createTempDirectory("bypassed-without")
        // Simulates the block mechanism having been bypassed: the fake client's "agent" manages
        // to write a ContextGraph artefact into the supposedly untouched clean copy.
        val client = FakeAgentClient { context ->
            Files.createDirectories(context.workingDir.resolve(".contextgraph"))
            Files.write(context.workingDir.resolve(".contextgraph").resolve("graph.db"), byteArrayOf(1, 2, 3))
            outcome()
        }
        val runner = AgentRunner(client)

        val record = runner.run(question(), Arm.WITHOUT_TOOLS, cleanDir, repeatIndex = 0, config = config)

        record.contaminated shouldBe true
    }

    test("AC-9 (review fix): a file changing in the WITH_TOOLS working copy during the run also marks the record contaminated") {
        // The hole flagged in review: the old presence-based check could never see contamination
        // in the WITH_TOOLS arm, because its working copy legitimately carries ContextGraph
        // artefacts already. The fingerprint-diff check must catch a *change* there regardless.
        val indexedDir = Files.createTempDirectory("bypassed-with")
        val graphDb = Files.createDirectories(indexedDir.resolve(".contextgraph")).resolve("graph.db")
        Files.write(graphDb, byteArrayOf(1, 2, 3))

        val bridge = FakeMcpToolBridge(emptyList())
        val client = FakeAgentClient { context ->
            // Simulates a bypassed `contextgraph index` mutating the already-indexed graph.
            Files.write(context.workingDir.resolve(".contextgraph").resolve("graph.db"), byteArrayOf(9, 9, 9))
            outcome()
        }
        val runner = AgentRunner(client, mcpToolBridgeFactory = { bridge })

        val record = runner.run(question(), Arm.WITH_TOOLS, indexedDir, repeatIndex = 0, config = config)

        record.contaminated shouldBe true
    }

    test("AC-9 (review fix, the trap): a normal WITH_TOOLS run is NOT contaminated merely because its working copy already carries artefacts") {
        // Deleting the old `arm == WITHOUT_TOOLS &&` guard outright would flip every WITH_TOOLS
        // run to contaminated=true, since that copy carries `.contextgraph/` by design. This is
        // the regression test proving the fingerprint-diff replacement avoids that trap.
        val indexedDir = Files.createTempDirectory("legit-with")
        val graphDb = Files.createDirectories(indexedDir.resolve(".contextgraph")).resolve("graph.db")
        Files.write(graphDb, byteArrayOf(1, 2, 3))

        val bridge = FakeMcpToolBridge(listOf(ToolDefinition("contextgraph_explore", "desc", "{}")))
        val client = FakeAgentClient { outcome() } // touches nothing in workingDir
        val runner = AgentRunner(client, mcpToolBridgeFactory = { bridge })

        val record = runner.run(question(), Arm.WITH_TOOLS, indexedDir, repeatIndex = 0, config = config)

        record.contaminated shouldBe false
    }

    test("a WITH_TOOLS bridge that touches workingDir only at construction time (harness connection-open cost) does not false-positive") {
        // Mirrors the real ContextGraphMcpToolBridge opening a SQLite connection: that happens
        // once, before the agent does anything, and must be absorbed into the AC-9 baseline
        // rather than read as the agent having mutated the working copy.
        val indexedDir = Files.createTempDirectory("connection-open-cost")
        Files.createDirectories(indexedDir.resolve(".contextgraph"))

        val client = FakeAgentClient { outcome() }
        val runner = AgentRunner(
            client,
            mcpToolBridgeFactory = { dir ->
                // Simulates a driver touching a journal/lock file the moment the connection opens.
                Files.write(dir.resolve(".contextgraph").resolve("graph.db-wal"), byteArrayOf(0))
                FakeMcpToolBridge(emptyList())
            }
        )

        val record = runner.run(question(), Arm.WITH_TOOLS, indexedDir, repeatIndex = 0, config = config)

        record.contaminated shouldBe false
    }

    test("cliInvocationAttempts reflects the guard's attempt count for the run") {
        val cleanDir = Files.createTempDirectory("cli-attempts")
        val client = FakeAgentClient { context ->
            context.bashExecutor.execute("contextgraph search leaked")
            context.bashExecutor.execute("ls -la")
            outcome()
        }
        val runner = AgentRunner(client)

        val record = runner.run(question(), Arm.WITHOUT_TOOLS, cleanDir, repeatIndex = 0, config = config)

        record.cliInvocationAttempts shouldBe 1
        // The blocked command must never have reached a real process -- if it had, this would
        // itself be the artefact-appearing scenario tested above, not a "blocked" scenario.
        record.contaminated shouldBe false
    }

    test("the PATH sentinel catches a Bash command that evades ContaminationGuard's regex via shell indirection") {
        // "X=contextgraph; $X search leaked" never puts the literal word "contextgraph" next to
        // a shell separator, so ContaminationGuard's string-level regex allows it through --
        // this is precisely the gap flagged in review. The real shell still resolves $X to
        // "contextgraph" via PATH at execution time, though, which is what the sentinel catches.
        val cleanDir = Files.createTempDirectory("sentinel-catch")
        val client = FakeAgentClient { context ->
            context.bashExecutor.execute("X=contextgraph; \$X search leaked")
            outcome()
        }
        val runner = AgentRunner(client)

        val record = runner.run(question(), Arm.WITHOUT_TOOLS, cleanDir, repeatIndex = 0, config = config)

        // Caught by the sentinel, not the regex -- and specifically NOT contaminated, because
        // the sentinel intercepted it before anything resembling the real CLI ever ran.
        record.cliInvocationAttempts shouldBe 1
        record.contaminated shouldBe false
    }

    test("a run whose tool call count reaches the ceiling is marked hitCeiling") {
        val cleanDir = Files.createTempDirectory("ceiling")
        val client = FakeAgentClient { outcome(toolCallCount = 40) }
        val runner = AgentRunner(client)

        val record = runner.run(question(), Arm.WITHOUT_TOOLS, cleanDir, repeatIndex = 0, config = config)

        record.hitCeiling shouldBe true
    }

    test("a run well under the ceiling is not marked hitCeiling") {
        val cleanDir = Files.createTempDirectory("under-ceiling")
        val client = FakeAgentClient { outcome(toolCallCount = 5) }
        val runner = AgentRunner(client)

        val record = runner.run(question(), Arm.WITHOUT_TOOLS, cleanDir, repeatIndex = 0, config = config)

        record.hitCeiling shouldBe false
    }

    test("AC-10: every measured field lands on the record") {
        val cleanDir = Files.createTempDirectory("full-record")
        val client = FakeAgentClient {
            outcome(toolCallCount = 7, fileReadCount = 2, inputTokens = 12_345, outputTokens = 678, finalAnswer = "because X calls Y")
        }
        val runner = AgentRunner(client)

        val q = question(id = "q-full")
        val record = runner.run(q, Arm.WITHOUT_TOOLS, cleanDir, repeatIndex = 2, config = config)

        record.questionId shouldBe "q-full"
        record.arm shouldBe Arm.WITHOUT_TOOLS
        record.repeatIndex shouldBe 2
        record.inputTokens shouldBe 12_345
        record.outputTokens shouldBe 678
        record.toolCallCount shouldBe 7
        record.fileReadCount shouldBe 2
        record.finalAnswer shouldBe "because X calls Y"
        record.costUsd shouldBe 0.01
        record.id.isNotBlank() shouldBe true
    }
})
