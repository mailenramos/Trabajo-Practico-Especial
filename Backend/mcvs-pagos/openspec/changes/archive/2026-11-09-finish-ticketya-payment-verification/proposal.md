# Proposal

## Why

The payment-service change has implementation and concurrency tests in place, but repository behavior and the final quality gate have not been verified against the configured PostgreSQL test database. Completing those checks will distinguish verified behavior from tests that were skipped because the database was unavailable.

## What Changes

- Verify repository lookups, refund aggregation, and pessimistic payment locking against PostgreSQL; make only focused test or repository corrections if these checks expose a defect.
- Run the standard Maven tests, package build, and strict OpenSpec validation, while keeping PostgreSQL tests explicitly gated by their environment prerequisites.
- Record any unavailable PostgreSQL prerequisite as a blocker rather than treating skipped tests as passed.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

None. This change only verifies existing behavior and does not alter the service contract.

## Impact

Potentially affected files are repository integration tests and, only if a verified defect requires it, the repository implementation. No API behavior, dependencies, `pom.xml`, database schema, or production configuration is intended to change.
