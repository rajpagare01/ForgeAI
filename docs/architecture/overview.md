# ForgeAI Architecture Overview

## Introduction

ForgeAI is an AI Engineering Operating System. 

It is important to state what ForgeAI is **NOT**: It is not a GitHub clone, and it does not implement Git hosting.

GitHub remains fully responsible for:
- Git repositories
- Commits and branching
- Pull requests
- Code hosting
- GitHub Actions

ForgeAI integrates with GitHub via APIs and webhooks, and adds:
- Identity and access management (multi-organization)
- Project and requirement management
- Engineering workflow and automation
- Testing integration and traceability
- AI engineering intelligence
- Engineering knowledge graph
- Requirement → Task → Code → PR → Test traceability

## Services

The system is built as a microservices architecture:

1. **Identity Service**: Manages users, organizations, teams, roles, permissions, and core authentication.
2. **Project Service**: Manages projects, tasks, sprints, and requirements.
3. **Source Control Service**: Integrates with external source control platforms (initially GitHub).
4. **Testing Service**: Manages test execution traceability and integration.
5. **AI Service**: Provides engineering intelligence and knowledge graphs (Python/FastAPI).

## Clean Architecture

Java services follow Clean Architecture principles:

- **Domain Layer**: Core business models and logic. Independent of all external frameworks.
- **Application Layer**: Use cases and interfaces (ports) to the outside world.
- **Infrastructure Layer**: Implementation of ports (e.g., database repositories, external API clients).
- **API Layer**: REST controllers and web endpoints.
