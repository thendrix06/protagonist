package capture.domain

/**
 * Event-local, pseudonymous subject track identifier assigned by the backend (for example `S01`).
 *
 * A track ID is never derived from biometric identity. It is valid only within one capture event and the
 * client must never reassign, merge or invent one: association decisions belong to the backend.
 */
@JvmInline
public value class TrackId(public val value: String) {
    init {
        require(value.isNotBlank()) { "TrackId must not be blank" }
    }

    override fun toString(): String = value
}

/** Backend detection event that a track belongs to. Retained verbatim by the server. */
@JvmInline
public value class EventId(public val value: String) {
    override fun toString(): String = value
}

/** Canonical capture job identifier. Only the server assigns these. */
@JvmInline
public value class JobId(public val value: String) {
    override fun toString(): String = value
}

/** Client-generated key that lets the backend deduplicate an authorization that was transmitted more than once. */
@JvmInline
public value class IdempotencyKey(public val value: String) {
    init {
        require(value.length in 8..128) { "IdempotencyKey must be 8..128 chars" }
    }

    override fun toString(): String = value
}

@JvmInline
public value class SectorId(public val value: String) {
    override fun toString(): String = value
}

/** Source of fresh idempotency keys. Production uses random UUIDs; tests use a deterministic sequence. */
public fun interface IdempotencyKeyFactory {
    public fun next(): IdempotencyKey
}

/** Wall clock in UTC epoch milliseconds. Injected everywhere so simulations and tests stay deterministic. */
public fun interface Clock {
    public fun nowMs(): Long
}
