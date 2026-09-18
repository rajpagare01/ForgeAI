# ADR 0001: Use GitHub for Git Hosting

## Context
ForgeAI aims to be an AI Engineering Operating System that tracks the entire software development lifecycle (Requirements -> Tasks -> Code -> PRs -> Tests). A core decision is whether ForgeAI should host Git repositories itself or integrate with an existing platform.

## Decision
ForgeAI will **integrate with GitHub** instead of implementing its own Git hosting platform. 

GitHub will remain responsible for:
- Git repository storage
- Commits and branching models
- Pull requests and code reviews
- GitHub Actions

ForgeAI will connect to GitHub via its REST/GraphQL APIs and webhooks to ingest data, track traceablity, and automate workflows.

## Rationale
- **Focus on Core Value**: ForgeAI's value proposition is engineering intelligence and workflow automation, not reinventing Git hosting (a solved problem).
- **Reduced Complexity**: Operating a scalable Git hosting service is operationally complex and resource-intensive.
- **Developer Experience**: Developers already use and trust GitHub; forcing them to migrate repositories to a new platform creates unnecessary friction.

## Consequences
- We must build a robust `source-control-service` to handle GitHub API rate limits, webhook delivery failures, and eventual consistency.
- ForgeAI cannot operate entirely offline or independently of a supported third-party Git provider.
