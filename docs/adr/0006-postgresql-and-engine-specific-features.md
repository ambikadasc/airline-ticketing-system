# ADR 0006 – PostgreSQL, Flyway, and the engine-specific features we rely on

**Status:** accepted

## Context
The design relies on a few database features for correctness and simplicity. Choosing the engine
on purpose, and recording what would change on another one, keeps that dependency visible.

## Decision
- **PostgreSQL 16.** The schema is owned by **Flyway** (forward-only migrations V1–V3, seed data
  in V4); Hibernate only validates (`ddl-auto=validate`).
- **Tests run on the same engine** via Testcontainers. No H2.
- **The PostgreSQL-specific features in use**, each commented where it appears in
  `db/migrations` and `db/schema.sql`:

| Feature | Where | Why | MySQL 8 equivalent |
| --- | --- | --- | --- |
| Partial unique index | `uq_active_seat … WHERE status IN ('ACTIVE','HELD')` | At most one live claim per seat; released rows are kept as history | A nullable column `active_seat_key = CONCAT(flight_instance_id, ':', seat_number)`, set while the seat is ACTIVE or HELD and NULL once RELEASED, with a plain `UNIQUE` index on it (MySQL unique indexes allow many NULLs) |
| `INSERT … ON CONFLICT DO NOTHING` | `FlightInstanceBulkWriter` | Idempotent instance generation | `INSERT IGNORE`, or `ON DUPLICATE KEY UPDATE id = id` |
| Regex `CHECK` (`~`) | airport code, seat letters | Format rules enforced by the database | `CHECK (code REGEXP '^[A-Z]{3}$')` (MySQL 8.0.16+) |
| `setval(pg_get_serial_sequence(…))` | V4 seed | Move the identity past explicit seed ids | `ALTER TABLE aircraft AUTO_INCREMENT = 4` |
| `SELECT … FOR UPDATE` with `lock_timeout` | flight lock | Fail fast under contention | `FOR UPDATE` with `innodb_lock_wait_timeout` |

## Alternatives
- **MySQL:** workable with the equivalents above. The partial index becomes an extra column the
  application must maintain, which is one more thing to get right.
- **H2 for tests:** faster to start, but it would not test the partial index, `ON CONFLICT` or
  lock behaviour that correctness depends on.

## Consequences
- The concurrency guarantee (ADR 0002) is tested against the real engine.
- `db/schema.sql` is a commented, readable copy of the migrated schema. Its tables, constraints
  and indexes were verified identical to a migrated database with `pg_dump --schema-only`.
- Porting to another engine means following this table plus re-running the concurrency tests.

## When to revisit
- An organisational standard requires another engine: apply the equivalents above and keep the
  concurrency tests as the acceptance gate.
