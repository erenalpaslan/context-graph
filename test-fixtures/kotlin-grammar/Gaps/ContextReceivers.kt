package com.example.gaps

// KNOWN GRAMMAR GAP (see KotlinGrammar.kt doc comment): fwcd/tree-sitter-kotlin does not
// support context receivers at all. The line below does not parse as a context-receiver
// function declaration -- it silently misparses as an ordinary top-level call expression
// `context(Logger)` followed by a completely ordinary `fun doWork()` with no receiver
// information attached. No syntax error is raised (tree.rootNode.hasError stays false),
// so this is NOT caught by the diagnostic path -- it is a silent gap, captured here only
// because it is baked into the committed golden snapshot.
context(Logger)
fun doWork() {
}

interface Logger
