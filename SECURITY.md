# Security Policy

## Supported versions

Security fixes are provided for the current SemEvoSQL release line. Until a newer supported release is published, `1.0.x` is the supported line.

## Reporting a vulnerability

Please do not open a public issue for a suspected vulnerability. Use the repository's **GitHub Security Advisories → Report a vulnerability** flow so details can be reviewed privately before disclosure.

Include, when available:

- affected version or commit;
- deployment topology and relevant security settings;
- reproducible steps or a minimal proof of concept;
- observed impact;
- any proposed mitigation.

Do not include production credentials, access tokens, customer data, or other secrets in the report.

## Security boundaries

SemEvoSQL is designed for a self-hosted, single-user deployment. Use read-only credentials for business databases, bind the console to loopback unless remote access is explicitly required, terminate TLS at a trusted reverse proxy or ingress for remote access, and keep the application and worker control-plane endpoints on private networks. The worker owns Docker daemon access and must be treated as a privileged host capability.

The execution worker is a trusted control-plane component because it owns Docker socket access. Generated Python runs in a separate pinned runner image with no network, a read-only root filesystem, a non-root user, dropped capabilities, and CPU/memory/process limits. Do not expose the worker's internal HTTP endpoint publicly.
