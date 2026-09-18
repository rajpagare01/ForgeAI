# ADR 0002: Clean Architecture and Microservices

## Context
As ForgeAI grows to encompass identity, project management, testing, and AI intelligence, the backend must remain scalable, maintainable, and loosely coupled.

## Decision
We will adopt a **Microservices Architecture** with strict **Clean Architecture** boundaries within each Java service.

### Microservice Boundaries
- The system is decomposed into business capability-aligned services (Identity, Project, Source Control, Testing, AI).
- **Database-per-service**: Each service owns its database (e.g., `forgeai_identity`). Services cannot access each other's databases directly.
- **Event-Driven Future**: Inter-service communication will eventually transition to an event-driven model (e.g., Kafka) to ensure loose coupling.

### Clean Architecture
Within Java services (Spring Boot):
- **Domain**: Pure Java objects. No Spring, JPA, or infrastructure dependencies.
- **Application**: Defines Use Cases (Inbound Ports) and required external dependencies (Outbound Ports).
- **Infrastructure**: Implements Outbound Ports (e.g., Spring Data JPA repositories, external HTTP clients).
- **API**: Implements Inbound Ports (e.g., REST Controllers).

## Rationale
- **Independent Scalability**: Different services have different scaling requirements (e.g., AI service vs Identity service).
- **Technology Diversity**: Allows the AI service to be written in Python while core business services use Java/Spring.
- **Maintainability**: Clean Architecture ensures business logic is testable in isolation and protected from framework churn.

## Consequences
- Requires disciplined enforcement of package dependencies.
- Increased operational complexity (requires API Gateway, Event Bus, distributed tracing in the future).
- Eventual consistency across service boundaries must be embraced.
