# Contributing to SemEvoSQL

Thanks for contributing. Keep changes focused on a concrete product, correctness, security, or maintainability outcome; avoid broad rewrites that only move code between packages.

## Development baseline

- JDK 17
- Node.js 22.12 or newer
- npm
- Docker with Compose v2 for standalone acceptance

Use the Maven wrapper and the repository Maven settings file rather than a machine-specific Maven configuration.

## Before opening a pull request

Backend changes must pass:

```bash
JAVA_HOME=/path/to/jdk17 ./mvnw -s .github/maven-settings.xml -pl backend -am clean verify
```

Frontend changes must pass:

```bash
cd frontend
npm ci
npm run verify
npm audit --audit-level=high
```

Deployment changes should also validate the Compose configuration and, when they affect startup/runtime behavior, run the standalone smoke path described in the README.

## Design expectations

- Preserve the governed query path: retrieval → semantic planning → compiler/preflight → execution → review → durable run/evidence.
- Do not bypass SQL safety, cost, project authorization, or semantic-version boundaries from compatibility APIs or MCP adapters.
- Keep Run/Event/Episode and semantic-version facts in their authoritative stores instead of creating parallel frontend or adapter state.
- Keep blocking I/O off WebFlux event-loop threads.
- Never commit credentials, private endpoints, machine-specific absolute paths, generated build output, or customer data.
- Add regression coverage for behavior changes and migration-sensitive changes.

## Database migrations

Flyway migrations are append-only after release. Do not rewrite an already released migration checksum. Destructive schema changes require an explicit compatibility and rollback design.

## Pull requests

Describe the user-visible or operational problem, the chosen boundary, tests performed, and any migration/deployment impact. Small, independently usable changes are preferred to large mixed refactors.
