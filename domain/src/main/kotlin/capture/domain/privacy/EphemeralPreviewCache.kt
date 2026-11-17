package capture.domain.privacy

import capture.domain.Clock
import capture.domain.TrackId

/**
 * RAM-only store for subject preview images.
 *
 * - Nothing is ever written to disk; there is no archive.
 * - Entries for unselected subjects expire after [unselectedTtlMs].
 * - The selected subject's preview may be retained for [selectedTtlMs] (still RAM-only).
 * - [clear] is called when the app is backgrounded, on memory pressure, and from Settings.
 *
 * The payload type is generic so the Android layer can store bitmaps while tests use byte arrays.
 */
public class EphemeralPreviewCache<T : Any>(
    private val clock: Clock,
    @Volatile public var unselectedTtlMs: Long = DEFAULT_UNSELECTED_TTL_MS,
    @Volatile public var selectedTtlMs: Long = DEFAULT_SELECTED_TTL_MS,
    private val maxEntries: Int = 32,
) {
    private data class Entry<T>(
        val payload: T,
        val storedAtMs: Long,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<TrackId, Entry<T>>()
    private var selected: TrackId? = null

    public fun put(
        trackId: TrackId,
        payload: T,
    ): Unit =
        synchronized(lock) {
            entries.remove(trackId)
            entries[trackId] = Entry(payload, clock.nowMs())
            while (entries.size > maxEntries) {
                val eldest = entries.keys.firstOrNull { it != selected } ?: entries.keys.first()
                entries.remove(eldest)
            }
        }

    public fun get(trackId: TrackId): T? =
        synchronized(lock) {
            purgeExpiredLocked()
            entries[trackId]?.payload
        }

    public fun setSelected(trackId: TrackId?): Unit = synchronized(lock) { selected = trackId }

    public fun purgeExpired(): Int = synchronized(lock) { purgeExpiredLocked() }

    public fun clear(): Unit = synchronized(lock) { entries.clear() }

    public val size: Int get() = synchronized(lock) { entries.size }

    private fun purgeExpiredLocked(): Int {
        val now = clock.nowMs()
        val before = entries.size
        entries.entries.removeAll { (id, entry) ->
            val ttl = if (id == selected) selectedTtlMs else unselectedTtlMs
            now - entry.storedAtMs > ttl
        }
        return before - entries.size
    }

    public companion object {
        public const val DEFAULT_UNSELECTED_TTL_MS: Long = 15_000
        public const val DEFAULT_SELECTED_TTL_MS: Long = 120_000
    }
}
