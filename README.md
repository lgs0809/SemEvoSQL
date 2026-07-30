<div align="center">

# SemEvoSQL

**A self-evolving NL2SQL platform**

Natural Language → Semantic Blueprint → Verified SQL → Durable Execution → Continuous Learning

[![GitHub Actions](https://img.shields.io/badge/-E9F3FF?style=flat-square&logo=githubactions&logoColor=2088FF)](https://github.com/lgs0809/SemEvoSQL/actions/workflows/ci.yml)[![CI](https://img.shields.io/github/actions/workflow/status/lgs0809/SemEvoSQL/ci.yml?branch=main&style=flat-square&label=CI&labelColor=2D333B)](https://github.com/lgs0809/SemEvoSQL/actions/workflows/ci.yml)
[![GitHub](https://img.shields.io/badge/-E8E8E8?style=flat-square&logo=github&logoColor=181717)](https://github.com/lgs0809/SemEvoSQL/tags)[![Release](https://img.shields.io/github/v/tag/lgs0809/SemEvoSQL?sort=semver&style=flat-square&label=release&color=5F70E1&labelColor=2D333B)](https://github.com/lgs0809/SemEvoSQL/tags)
![OpenJDK](https://img.shields.io/badge/-FDF3E6?style=flat-square&logo=openjdk&logoColor=ED8B00)![Java 17](https://img.shields.io/badge/Java-17-ED8B00?style=flat-square&labelColor=2D333B)
![Spring Boot](https://img.shields.io/badge/-F0F7EC?style=flat-square&logo=springboot&logoColor=6DB33F)![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F?style=flat-square&labelColor=2D333B)
![Vue](https://img.shields.io/badge/-EDF9F4?style=flat-square&logo=vuedotjs&logoColor=4FC08D)![Vue 3](https://img.shields.io/badge/Vue-3-4FC08D?style=flat-square&labelColor=2D333B)
![PostgreSQL](https://img.shields.io/badge/-ECF0FC?style=flat-square&logo=postgresql&logoColor=4169E1)![PostgreSQL + pgvector](https://img.shields.io/badge/PostgreSQL-pgvector-4169E1?style=flat-square&labelColor=2D333B)
[![Apache](https://img.shields.io/badge/-FBE9EA?style=flat-square&logo=apache&logoColor=D22128)![License](https://img.shields.io/badge/License-Apache--2.0-D22128?style=flat-square&labelColor=2D333B)](LICENSE)

</div>

SemEvoSQL turns business questions into verified, read-only SQL. Instead of letting an LLM directly generate and execute arbitrary SQL, it places a governed semantic layer, deterministic compilation, and query preflight between the model and your database.

## Quick start

Requirements: Docker with Compose v2.

```bash
git clone https://github.com/lgs0809/SemEvoSQL.git
cd SemEvoSQL
./scripts/init-deployment-env.sh
./scripts/start-semevosql.sh
```

Open **http://127.0.0.1:23000/semevosql/**, then:

1. configure Chat, Embedding, and optional Rerank providers in the Web Console;
2. connect a read-only MySQL or PostgreSQL business database;
3. create a project and publish its semantic model;
4. ask questions in natural language.

SemEvoSQL 1.0 runs as a self-hosted single-user application and does not ship a built-in account or login system. Backend, frontend, and demo database ports bind to `127.0.0.1` by default. The startup script refuses non-loopback exposure unless `SEMEVOSQL_ALLOW_REMOTE_BIND=true` is explicitly set after a trusted external access layer has been put in place.

## Deployment and execution boundary

The application process never owns Docker daemon access. Generated Python runs through the separate execution worker and the versioned `semevosql/python-runner:1.0.0` image with no network, a read-only root filesystem, dropped Linux capabilities, process/memory/CPU limits, and a non-root runtime user. Runtime package installation is disabled. The worker still controls the Docker socket and must therefore be treated as a trusted internal control-plane component; do not expose its internal HTTP endpoint. The startup script detects the Docker socket group automatically, while `SEMEVOSQL_DOCKER_GID` remains available as an explicit override for unusual hosts.

SemEvoSQL 1.0 should not be exposed directly to the Internet. If remote access is required, keep the application behind a trusted reverse proxy, VPN, or ingress that provides the deployment's access control, HTTPS, firewall, hostname, and certificate policy before opting into non-loopback binding.

## Highlights

- **Semantic-first NL2SQL** — business metrics, dimensions, relationships, time semantics, aliases, rules, and evidence live in a versioned Semantic Catalog.
- **Verified SQL** — the model produces a Semantic Blueprint; SemEvoSQL compiles it, applies SQL policy checks, runs preflight validation, and only then executes against a read-only data source.
- **Hybrid retrieval** — Exact, BM25, and Vector retrieval are fused with RRF; when configured and available, Rerank refines the governed candidate set before planning.
- **Durable execution** — query runs, checkpoints, clarification, evidence, review, and recovery survive browser or network interruptions.
- **Continuous learning** — validated corrections and successful query cases can be replayed and published into later semantic versions.
- **Agent-ready** — published projects can expose governed query capabilities through Streamable HTTP MCP.

## How it works

```text
Natural-language question
          │
          ▼
 Exact + BM25 + Vector → RRF → optional Rerank
          │
          ▼
  Semantic Blueprint
          │
          ▼
 Compiler + Query Preflight
          │
          ▼
     Verified SQL
          │
          ▼
 Read-only data source
          │
          ▼
 Review → Evidence → Learning
```

The model reasons over semantic context; SemEvoSQL keeps SQL generation, validation, execution, and learning inside explicit product boundaries.

## MCP

A published project can expose a project-scoped Streamable HTTP MCP endpoint with two stable tools: **`query`** and **`query_status`**. External agents ask business questions and poll durable execution through this narrow contract; SemEvoSQL keeps semantic retrieval, planning, validation, SQL compilation, execution, version pinning, and recovery behind the server boundary.

## Development

Backend development requires JDK 17. Frontend development requires Node.js 22.12+ and npm.

```bash
./mvnw -pl backend -am verify
```

```bash
cd frontend
npm ci
npm run build
```

CI also verifies release hygiene and portable Docker/Compose startup.

## License

SemEvoSQL is released under the [Apache License 2.0](LICENSE).
