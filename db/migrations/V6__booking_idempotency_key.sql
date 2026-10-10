-- V6: client-supplied idempotency key for booking creation. A retried POST /bookings that carries
-- the same Idempotency-Key header returns the booking it created the first time, instead of a
-- seat conflict that hides the reference. The key is stored on the booking it created; failed
-- attempts store nothing (nothing was changed, so re-running them is correct).

ALTER TABLE booking
    ADD COLUMN idempotency_key VARCHAR(64),
    -- SHA-256 (hex) of the normalised request, so the same key with a different request is refused.
    ADD COLUMN request_hash    VARCHAR(64),
    ADD CONSTRAINT chk_booking_idempotency CHECK ((idempotency_key IS NULL) = (request_hash IS NULL));

-- One booking per key. Partial, so bookings made without a key (NULL) never collide.
-- MySQL: a plain UNIQUE index on idempotency_key gives the same guarantee (MySQL unique indexes
-- allow many NULLs).
CREATE UNIQUE INDEX uq_booking_idempotency_key ON booking (idempotency_key) WHERE idempotency_key IS NOT NULL;
