# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | ✅        |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Send a report to **erenalpaslan97@gmail.com** with the subject line:

```
[SECURITY] ContextGraph - <brief description>
```

Include as much of the following as possible:

- Type of vulnerability (e.g. path traversal, SQL injection, arbitrary code execution)
- The component affected (e.g. `SqliteStorageAdapter`, `IngestPipeline`, MCP server)
- Steps to reproduce
- Proof-of-concept code or commands (if available)
- Potential impact and severity assessment

You will receive an acknowledgement within **48 hours** and a resolution timeline within **7 days**.

## Scope

### In scope

- **Path traversal** — file discovery or extraction reading files outside the intended project root
- **SQL injection** — unsanitized input reaching the SQLite layer via the query or storage modules
- **Arbitrary file write** — report generation or export writing to unintended paths
- **MCP server input validation** — malformed tool arguments causing unintended behavior
- **LiteLLM / semantic extractor** — prompt injection or credential leakage via the HTTP client
- **Dependency vulnerabilities** — CVEs in direct dependencies (Exposed, Flyway, PDFBox, JSQLParser, etc.)

### Out of scope

- Vulnerabilities in the indexed project's own files (ContextGraph reads but does not execute them)
- Denial-of-service via extremely large files (mitigated by `maxFileSizeBytes` config)
- Issues only reproducible with a deliberately malicious `.contextgraph/config.json`

## Security Considerations

ContextGraph is a **local-first tool** — it reads files from your filesystem and writes to a local SQLite database. There is no network exposure by default.

**MCP server (stdio mode):** The MCP server communicates over stdio and is only accessible to the process that spawned it (e.g. Claude Desktop). No network port is opened.

**Semantic extraction:** When `litellm.enabled = true`, file contents are sent to the configured LiteLLM endpoint. Ensure the endpoint is trusted and that no secrets are present in indexed files. Use `excludePatterns` to skip sensitive paths:

```json
{
  "excludePatterns": [
    "**/.env*",
    "**/secrets/**",
    "**/*.pem",
    "**/*.key"
  ]
}
```

**SQLite database:** The graph database at `.contextgraph/graph.db` contains extracted content from your project. Treat it with the same sensitivity as the source files.

## Disclosure Policy

We follow **coordinated disclosure**. Once a fix is available we will:

1. Release a patched version
2. Credit the reporter in the release notes (unless anonymity is requested)
3. Publish a brief advisory in the GitHub Security Advisories tab
