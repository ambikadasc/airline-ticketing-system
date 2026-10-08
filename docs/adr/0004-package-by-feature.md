# ADR 0004 – Package by feature, layered inside; patterns only where the problem has the shape

**Status:** accepted

## Context
The brief grades clean architecture, separation of concerns and readability. The code must be
explainable line by line, so structure should help a reader find things, not add ceremony.

## Decision
- **Packages by feature:** `airport`, `aircraft`, `schedule`, `flight`, `booking`, `common`.
  Inside a feature, by layer: `api` (controllers, request records), `service` (use cases,
  transactions, result records), `domain` (JPA entities with behaviour), `persistence`.
- **Dependencies point one way:** `booking → flight → aircraft, airport` and
  `schedule → flight, aircraft, airport`; everything may use `common`. Two placements follow
  from that:
  - The instance generator and window job live in `schedule`.
  - The seat map reads booking rows with SQL in `flight.persistence`, so `flight` never imports
    `booking` code.
- **Three ArchUnit rules enforce this** (`ArchitectureTest`): controllers don't touch repositories;
  nothing depends on `api`; no cycles between features.
- **JPA entities are the domain model.** Behaviour lives on them (`reserve`, `cancel`, `confirm`,
  `expireHold`); status changes go through an enum state machine; there are no public status
  setters.
- **Services return `*Result` records** used directly as response bodies, so there is no second
  mapping layer.
- **Patterns only where the problem has that shape:**
  - Strategy for the booking policy (two real implementations).
  - Strategy for the cancellation policy. This is a deliberate exception with one
    implementation, because cancellation rules (cut-off windows, admin override) are the most
    likely next change.
  - Value object `SeatLayout`; static factories.
  - No interface for `PnrGenerator` or for services.
- **Booking validation is one class, `BookingRequestValidator`, with one private method per rule.**
  If the rules grow, the refactor path is a list of rule objects behind one interface (Chain of
  Responsibility or Specification), so that adding a rule means adding a class.

## Alternatives
- **Package by layer** (`controllers/`, `services/`, …): feature boundaries become invisible
  and unrelated code sits together.
- **Hexagonal / ports and adapters with a separate persistence model:** more types and mapping
  for no second adapter.
- **A rule-list validator today:** more classes for four simple rules.

## Consequences
- Related code changes together; a feature can be read top to bottom in one folder.
- Some cross-feature reads are plain SQL instead of a service call. That's documented, and it's
  what keeps the dependency graph acyclic.

## When to revisit
- A feature grows large enough to be split out as its own service; the package boundary is the
  seam.
- Booking validation gains several more rules: introduce the rule list.
