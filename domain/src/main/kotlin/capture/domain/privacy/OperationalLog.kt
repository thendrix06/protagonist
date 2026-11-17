package capture.domain.privacy

import capture.domain.Clock

/**
 * Privacy-preserving operational log.
 *
 * Only a closed set of structured fields can be recorded: identifiers (pseudonymous track/job IDs,
 * idempotency keys), statuses, sequence numbers and durations. There is deliberately no API for free text,
 * images, frames, biometric templates or names, so they cannot leak into logs by accident.
 */
public class OperationalLog(
    private val clock: Clock,
    private val capacity: Int = 500,
    private val sink: (Record) -> Unit = {},
) {
    public enum class Kind {
        SELECT,
        ENQUEUE_SENT,
        ENQUEUE_REFUSED,
        ACK,
        JOB_STATUS,
        LINK,
        SNAPSHOT,
        SEQ_GAP,
        SENSOR,
        MODE,
        SECTOR,
        HID,
    }

    public data class Record(
        val atMs: Long,
        val kind: Kind,
        val fields: Map<Field, String>,
    ) {
        override fun toString(): String = "$atMs ${kind.name} " + fields.entries.joinToString(" ") { "${it.key.key}=${it.value}" }
    }

    /** Allow-listed fields. Values are validated to be short identifier-like tokens. */
    public enum class Field(
        public val key: String,
    ) {
        TRACK("track"),
        JOB("job"),
        IDEMPOTENCY("idem"),
        STATUS("status"),
        SEQ("seq"),
        MODALITY("mode"),
        SECTOR("sector"),
        REASON_CODE("reason"),
        DURATION_MS("ms"),
        COUNT("n"),
    }

    private val lock = Any()
    private val buffer = ArrayDeque<Record>()

    public fun record(
        kind: Kind,
        vararg fields: Pair<Field, Any?>,
    ) {
        val sanitized =
            fields
                .filter { it.second != null }
                .associate { (field, value) -> field to sanitize(value.toString()) }
        val rec = Record(clock.nowMs(), kind, sanitized)
        synchronized(lock) {
            buffer.addLast(rec)
            while (buffer.size > capacity) buffer.removeFirst()
        }
        sink(rec)
    }

    public fun snapshot(): List<Record> = synchronized(lock) { buffer.toList() }

    public fun clear(): Unit = synchronized(lock) { buffer.clear() }

    public companion object {
        private val allowed = Regex("[A-Za-z0-9_.:\\-]{1,64}")

        /** Anything that is not a short identifier token is replaced, never logged. */
        public fun sanitize(value: String): String = if (allowed.matches(value)) value else "<redacted>"
    }
}
