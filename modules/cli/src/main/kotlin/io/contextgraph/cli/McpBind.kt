package io.contextgraph.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

/**
 * `contextgraph mcp` -- the subcommand group. Carries no behaviour of its own; Clikt prints
 * the group's help when it is invoked without a subcommand. Mirrors [ConfigCommand].
 */
class McpCommand : CliktCommand("mcp") {
    override fun help(context: Context) = "MCP server integration helpers"
    override fun run() = Unit
}

/**
 * `contextgraph mcp bind` -- prints the Claude Code `.mcp.json` block for this project.
 *
 * The emitted `command`/`args` pair is deliberately **portable**: it names `contextgraph` on
 * `PATH` rather than an absolute path, and resolves the project root at launch time rather
 * than baking one in. Both matter because `.mcp.json` is committed and shared with the team
 * -- an absolute path would name one developer's machine, and a hardcoded project directory
 * would break for everyone else. The `cd` is not optional: the server resolves
 * `.contextgraph/` relative to the working directory ([projectRoot] is `Path.of(".")`), so
 * launching Claude Code from a subdirectory would otherwise point it at the wrong root. The
 * `|| pwd` fallback keeps the block working outside a git repository, and keeps git's
 * "not a git repository" complaint off the MCP transport's stderr.
 *
 * The command inspects nothing -- not whether this is a git repository, not whether a graph
 * exists. [CONFIG] is a constant, so it is safe to run before `index` and identical wherever
 * it runs. Nothing is written to stderr either, which is what makes
 * `contextgraph mcp bind > .mcp.json` a valid way to create the file.
 */
class McpBindCommand : CliktCommand("bind") {
    override fun help(context: Context) =
        "Print the Claude Code .mcp.json block that binds this project to the ContextGraph MCP server"

    override fun run() = echo(CONFIG)

    companion object {
        /**
         * Held as a literal rather than built through the JSON DSL: nothing here is computed,
         * and this way what the command prints can be read -- and diffed against the README --
         * without mentally rendering a builder.
         */
        val CONFIG = """
            {
              "mcpServers": {
                "contextgraph": {
                  "command": "sh",
                  "args": ["-c", "cd \"${'$'}(git rev-parse --show-toplevel 2>/dev/null || pwd)\" && exec contextgraph serve-mcp"]
                }
              }
            }
        """.trimIndent()
    }
}
