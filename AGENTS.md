# AGENTS.md

ContextGraph — a code understanding engine. Kotlin, Gradle, Java 17.

```bash
./gradlew build                     # build all modules
./gradlew test                      # all tests
./gradlew :modules:query:test       # one module
./gradlew :modules:cli:installDist  # CLI launcher; do not use :modules:cli:run
```

**Read [`CLAUDE.md`](CLAUDE.md) before changing anything.** It is the canonical
guidance for this repository and covers the ingest architecture, the
thirteen-module layout, the CLI, the MCP surface, the test conventions, and the
committed graph baseline — including which database file you may write to and
which one you must never write.
