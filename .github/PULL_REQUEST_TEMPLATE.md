## What changed

Describe the concrete product, correctness, security, or maintainability problem and the boundary changed to address it.

## Validation

- [ ] Backend `clean verify` passes when backend code changed.
- [ ] Frontend `npm run verify` passes when frontend code changed.
- [ ] Deployment/Compose smoke was run when deployment behavior changed.
- [ ] Regression coverage was added or updated for changed behavior.

## Compatibility and operations

- [ ] No released Flyway migration was rewritten.
- [ ] No credentials, private endpoints, customer data, or machine-specific absolute paths were introduced.
- [ ] API/MCP/error-contract changes are documented when externally observable.
- [ ] Security, authentication, authorization, and execution-boundary impact was reviewed when applicable.

## Notes for reviewers

Call out migration impact, rollback considerations, known limitations, or follow-up work that should not block this change.
