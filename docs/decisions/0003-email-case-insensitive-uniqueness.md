# ADR 0003: Email Case-Insensitive Uniqueness Strategy

## Status
Accepted

## Context
User email addresses must be unique in the `users` table. However, RFC 5321 specifies that the local part of an email address is technically case-sensitive, while in practice virtually all email providers treat addresses as case-insensitive. Users frequently register with `User@Example.com` and attempt to log in with `user@example.com`.

Three PostgreSQL approaches were evaluated:

1. **`CITEXT` extension** — `CREATE EXTENSION citext; email CITEXT NOT NULL UNIQUE`
2. **Lowercase normalization at application layer** — store `lower(email)`, index normally
3. **Functional unique index** — store original casing, index on `lower(email)`

## Decision
**Use a functional unique index: `CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));`**

The application layer queries using `lower(email)` for all lookups and uniqueness checks.

## Rationale

- `CITEXT` introduces an optional extension dependency that may not be available in all managed PostgreSQL environments (e.g., certain RDS configurations, restricted PaaS setups).
- Storing only lowercase loses the user's original email casing, which is cosmetically important (we display `User@Example.com` back to the user, not `user@example.com`).
- The functional index approach:
  - Enforces uniqueness at the database level (most important property)
  - Preserves original email casing for display
  - Works in all PostgreSQL environments without extensions
  - Is explicit and easy to understand in the schema

## Consequences
- All application queries involving email lookup **must** use `lower(:email)` as the filter
- No `UNIQUE(email)` inline constraint — only the functional index enforces uniqueness
- Registration flow must check uniqueness via the index before insert (the index itself will raise a unique violation on conflict)
