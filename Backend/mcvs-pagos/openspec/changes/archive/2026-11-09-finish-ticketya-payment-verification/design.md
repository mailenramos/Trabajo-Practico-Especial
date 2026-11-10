# Design

## Context

The repositories and PostgreSQL concurrency test class already exist. The integration tests use the `test` profile and run only when `RUN_POSTGRESQL_TESTS=true`; the profile connects to `ticketya_payments_test` and requires database credentials.

## Goals / Non-Goals

**Goals:**

- Verify repository queries and pessimistic locking against a real PostgreSQL instance.
- Complete the configured Maven and strict OpenSpec quality checks without treating gated tests as successful executions.

**Non-Goals:**

- Change payment/refund API behavior, schema, runtime configuration, or dependencies.
- Enable PostgreSQL tests by default or embed credentials in the repository.

## Decisions

- Reuse the existing PostgreSQL test profile and concurrency tests. Add focused integration coverage only for repository operations not already exercised by service-level scenarios.
- Keep PostgreSQL tests opt-in. Run them only when `RUN_POSTGRESQL_TESTS=true`, `DB_PASSWORD`, and the test database are available; otherwise report that verification as blocked, not passed.
- Run Maven tests, package, and strict OpenSpec validation as separate quality-gate checks so failures can be attributed to the relevant stage.

## Risks / Trade-offs

- [Risk] Environment-dependent PostgreSQL checks cannot run on every machine → [Mitigation] preserve the explicit gate and report prerequisites and outcome.
- [Risk] Concurrency behavior may pass in mocks but fail against the database → [Mitigation] require real PostgreSQL execution before claiming the repository/lock verification is complete.
