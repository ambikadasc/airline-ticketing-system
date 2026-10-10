# ADR 0007 – Idempotency key on booking creation

**Status:** accepted

## Context
A client that sends `POST /bookings`, loses the response (timeout, dropped connection) and
retries cannot create a duplicate: the seats are the natural key, so the retry meets
`uq_active_seat` and gets 409 `SEAT_UNAVAILABLE`. What it cannot do is learn the reference of the
booking its first attempt created, and that reference is the only way to open or cancel the
booking (ADR 0005). Retrying safely is a basic expectation of a booking API.

## Decision
- **An optional `Idempotency-Key` request header** on `POST /bookings`, 1–64 characters of
  letters, digits, `-` and `_` (a UUID or an order number). Without it, behaviour is exactly as
  before.
- **The key is stored on the booking it created**, with a SHA-256 fingerprint of the normalised
  request (`booking.idempotency_key`, `booking.request_hash`; both null without the header). A
  repeat with the same key and the same fingerprint returns that booking: the same 201,
  `Location` and body, with the booking's current status. The same key with a different
  fingerprint is refused with 422 `IDEMPOTENCY_KEY_REUSED`.
- **Looked up under the flight lock.** `createBooking` validates, locks the flight row, releases
  expired holds, and only then looks the key up. Concurrent retries carry the same flight id, so
  they queue on the same lock and the second sees the first's committed row (READ COMMITTED) and
  replays it. No extra locking or in-memory state.
- **The database enforces the rest**: a partial unique index `uq_booking_idempotency_key`
  (one booking per key; rows without a key never collide) and a check that key and fingerprint
  are set together. The one race the flight lock cannot serialise, the same key sent at the same
  instant for two different flights, ends at the unique index: the loser's transaction rolls
  back and the handler answers 422 `IDEMPOTENCY_KEY_REUSED`.
- **Only successful bookings are recorded.** A failed attempt (400, 404, 409) changed nothing,
  so re-running it is correct and needs no record.

## Alternatives considered
- **A separate `idempotency_key` table** holding key, fingerprint and a stored response. More
  general (any endpoint, any status), but a second table, an expiry job and a serialised
  response to keep in step with the booking's real state. The key here identifies exactly one
  booking, so the booking row is the right home; the replay is built from the live row.
- **Checking the key before taking the lock.** Cheaper for the common case, but two concurrent
  retries would both miss and the second would then book the seats again or hit the seat
  conflict; the lookup moves after the lock so the retry sees the committed booking.
- **Replaying a cached response verbatim.** Would show a booking as `CONFIRMED` after it was
  cancelled. The live row is returned instead, with its current status, which is what the client
  would get from `GET /bookings/{reference}`.
- **Making the header mandatory.** Not asked for by the brief; the API stays usable from a plain
  curl, and the header is documented as the way to retry safely.

## Consequences
- Retries are safe end to end for clients that send the header; the reference is never lost.
- The seat guarantee is unchanged: the unique seat index and the flight lock still decide who
  gets a seat; the key only answers "what happened to my request".
- Keys are never expired. A key is 64 bytes on a booking row, and bookings are kept anyway; if
  key reuse across years ever mattered, a client would prefix keys with a date.
- The hash is of the request as normalised by validation (upper-cased seat numbers, same
  passenger order), so `12a` and `12A` are the same request and a reordered passenger list is
  not; the latter is deliberate, as the booking is ordered too.
