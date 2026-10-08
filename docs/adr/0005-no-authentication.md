# ADR 0005 – No authentication; admin endpoints separated by path; bookings by reference

**Status:** accepted

## Context
The brief puts user accounts and authentication out of scope. It still separates the back office
(schedule creation) from customers (search, book, cancel), and customers need a way to find their
booking again.

## Decision
- **No authentication or authorisation in this service.**
- **Admin endpoints live under `/api/v1/admin/…`**, so a gateway or Spring Security can protect
  them with role-based access without touching the controllers' code.
- **Bookings are retrieved, confirmed and cancelled by the booking reference alone.** The
  reference is a 6-character PNR from a 32-symbol alphabet with no look-alike characters, made
  with `SecureRandom` (about 1 billion combinations), so it is not guessable and works as a
  bearer secret.

## Alternatives
- **Spring Security with users and roles:** out of scope, and it adds setup to every test and
  demo.
- **Reference plus surname for lookup and cancel:** a reasonable extra check without accounts.
  It was left out to keep the brief's flows simple, and is documented as a future step: a
  mismatch would return 404 like an unknown reference, and a strict match would split
  `passenger_name` into first and last name (an additive migration).

## Consequences
- Anyone who can reach the admin path can create schedules. Acceptable for this assignment; must
  not be deployed like that.
- Anyone holding a reference can cancel that booking, as with many airline "manage booking" pages
  that take only the PNR.

## When to revisit
- Any deployment beyond a demo: add authentication and role-based access on `/admin`.
- Self-service is exposed publicly without accounts: add the surname check.
