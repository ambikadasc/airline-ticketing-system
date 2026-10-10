# ADR 0003 – Optional seat hold behind a flag, with lazy expiry

**Status:** accepted

## Context
The brief describes one-step booking: seats are confirmed immediately. Real checkouts often hold
seats while the customer pays. We wanted to show how the design extends to that, without
changing the behaviour the brief asks for.

## Decision
- The flag `airline.seat-hold.enabled` (default `false`), with the hold length
  `airline.seat-hold.ttl` (default 10 minutes).
- A **Strategy**, `BookingPolicy`: `ImmediateConfirmationPolicy` (default) or `SeatHoldPolicy`.
  It is chosen once in `BookingPolicyConfig`, **the only place the flag is read**.
- With the flag on, `POST /bookings` creates a `HELD` booking with `HELD` seats and
  `hold_expires_at`. `POST /bookings/{reference}/confirm` confirms it while the hold is live;
  otherwise 409 `HOLD_EXPIRED`.
- **Lazy expiry, no scheduler:** every write that locks a flight first releases that flight's
  overdue holds (booking `EXPIRED`, seats `RELEASED`, counter restored). It runs under the flight
  lock, so it cannot race a confirmation.
- **Reads never write; they compute around overdue holds:**
  - the seat map shows such seats `AVAILABLE`
  - search adds them back to the counter
  - `GET /bookings/{reference}` reports `EXPIRED`
- The same unique index covers `ACTIVE` and `HELD` seats, so a held seat is as protected as a
  confirmed one.

## Alternatives
- **Always-on holds:** changes the brief's behaviour.
- **A scheduled clean-up job:** another moving part with its own failure modes. Lazy expiry
  plus read-time rules give the same observable behaviour without one.
- **Separate hold tables:** duplicates the seat-claim logic and the uniqueness guarantee.

## Consequences
- With the flag off there are never `HELD` rows: expiry and the read rules find nothing. The whole
  default test suite runs unchanged, which proves the default behaviour is untouched.
- A flag-on test class covers: hold then confirm, expiry then another customer books, confirm after
  expiry, cancel of a hold, and a confirm-vs-expiry race (exactly one wins, decided by the clock).
- An overdue hold stays in the database as `HELD` until the next write on its flight.
  Reporting directly from the table would have to apply the same rule.

## When to revisit
- Holds must be released on flights that see no further writes, e.g. for reporting accuracy:
  add a small scheduled sweep.
- Payment integration arrives: confirmation would be driven by the payment outcome. Size
  `airline.seat-hold.ttl` above the payment flow's worst-case latency so that a slow gateway
  rarely outlives the hold; when it does, the re-check under the flight lock on confirmation is
  the backstop (409 `HOLD_EXPIRED`, nothing stored, the payment to be refunded by the caller),
  so a seat that expired and was resold during a slow payment can never be confirmed twice.
