# Changelog

All notable changes to SemEvoSQL are documented here.

## 1.0.0

SemEvoSQL 1.0.0 establishes the first public release of the self-evolving NL2SQL platform.

### Highlights

- Project-scoped semantic models with controlled publication and rollback.
- Semantic Evolution built around Episodes, semantic gaps, change sets, replay validation, and semantic versioning.
- Hybrid semantic retrieval with exact matching, BM25, vector search, RRF fusion, and required reranking.
- Governed SQL compilation, query preflight, cost and safety guards, and durable execution.
- Runtime clarification, correction, diagnosis, and learning workflows with auditable evidence.
- Remote MCP deployment with project binding, durable query handles, explicit Episode continuity, and the `query` / `query_status` tool surface.
- Configurable Chat, Embedding, and Rerank providers through the web console.
- Standalone Docker deployment, release hygiene checks, CI quality gates, and browser acceptance coverage.
- Public-facing web console terminology separated from internal execution and governance implementation details.

### Compatibility

- Java 17 backend.
- Node.js 22.12+ frontend toolchain.
- PostgreSQL metadata store with pgvector support.
- OpenAI-compatible model endpoints for supported model roles.

### Release policy

Semantic model PATCH and MINOR evolution can activate automatically after validation; MAJOR business-baseline changes require explicit human promotion. Rollback switches the active semantic-version pointer without fabricating a new version.
