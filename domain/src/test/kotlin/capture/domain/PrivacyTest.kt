package capture.domain

import capture.domain.privacy.EphemeralPreviewCache
import capture.domain.privacy.OperationalLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivacyTest {
    private var now = 1_000L
    private val clock = Clock { now }

    @Test fun unselectedPreviewsExpireAfterTtlSelectedRetainedLonger() {
        val cache = EphemeralPreviewCache<ByteArray>(clock, unselectedTtlMs = 1_000, selectedTtlMs = 5_000)
        cache.put(TrackId("S01"), byteArrayOf(1))
        cache.put(TrackId("S02"), byteArrayOf(2))
        cache.setSelected(TrackId("S02"))
        now += 1_500
        assertNull(cache.get(TrackId("S01")), "unselected preview must expire")
        assertNotNull(cache.get(TrackId("S02")), "selected preview retained")
        now += 4_000
        assertNull(cache.get(TrackId("S02")))
        assertEquals(0, cache.size)
    }

    @Test fun clearDropsEverythingAndCapacityIsBounded() {
        val cache = EphemeralPreviewCache<ByteArray>(clock, maxEntries = 2)
        repeat(5) { cache.put(TrackId("S0$it"), byteArrayOf(it.toByte())) }
        assertEquals(2, cache.size)
        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test fun operationalLogOnlyAcceptsIdentifierTokens() {
        val log = OperationalLog(clock)
        log.record(
            OperationalLog.Kind.ENQUEUE_SENT,
            OperationalLog.Field.TRACK to "S01",
            OperationalLog.Field.REASON_CODE to "John Smith face template base64==",
        )
        val rec = log.snapshot().single()
        assertEquals("S01", rec.fields[OperationalLog.Field.TRACK])
        assertEquals("<redacted>", rec.fields[OperationalLog.Field.REASON_CODE])
        assertFalse(rec.toString().contains("Smith"))
        assertTrue(rec.toString().contains("track=S01"))
    }

    @Test fun operationalLogIsBounded() {
        val log = OperationalLog(clock, capacity = 3)
        repeat(10) { log.record(OperationalLog.Kind.LINK, OperationalLog.Field.COUNT to it) }
        assertEquals(listOf("7", "8", "9"), log.snapshot().map { it.fields.getValue(OperationalLog.Field.COUNT) })
    }
}
