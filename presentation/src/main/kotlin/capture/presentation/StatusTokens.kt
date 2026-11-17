package capture.presentation

import capture.domain.JobStatus
import capture.domain.LinkStatus
import capture.domain.TrackState

/**
 * Status glyphs. Every status is distinguishable by glyph AND text label, never by color alone
 * (color-blind safe). The Compose design system draws these shapes.
 */
public enum class StatusGlyph {
    FILLED_CIRCLE,
    HALF_CIRCLE,
    HOLLOW_RING,
    QUESTION_DIAMOND,
    HOURGLASS,
    SQUARE,
    TRIANGLE_RIGHT,
    CROSSHAIR,
    DOT_PULSE,
    CHECK,
    CROSS,
    SLASH,
    EXCLAMATION,
    WARNING_TRIANGLE,
    BROKEN_LINK,
}

/** Colors as 0xAARRGGBB so they can be verified (contrast, CVD separation) without Android. */
public data class StatusStyle(
    val glyph: StatusGlyph,
    val label: String,
    val containerArgb: Long,
    val contentArgb: Long,
)

public object StatusTokens {
    /** Observation/background surface of the operator UI (dark theme). */
    public const val SURFACE_ARGB: Long = 0xFF101418
    public const val ON_SURFACE_ARGB: Long = 0xFFE6E9ED

    /** Selection highlight: thick cyan outline plus "SELECTED" text; distinct from every status hue. */
    public const val SELECTION_ARGB: Long = 0xFF00E5FF

    public fun track(state: TrackState): StatusStyle =
        when (state) {
            TrackState.ACTIVE -> StatusStyle(StatusGlyph.FILLED_CIRCLE, "ACTIVE", 0xFF2E9E6B, 0xFF000000)
            TrackState.OCCLUDED -> StatusStyle(StatusGlyph.HALF_CIRCLE, "OCCLUDED", 0xFFF2B33D, 0xFF1A1200)
            TrackState.LOST -> StatusStyle(StatusGlyph.HOLLOW_RING, "LOST", 0xFF5D6670, 0xFFFFFFFF)
            TrackState.UNCERTAIN -> StatusStyle(StatusGlyph.QUESTION_DIAMOND, "UNCERTAIN", 0xFFC9A0FF, 0xFF1A0D33)
        }

    public fun job(status: JobStatus): StatusStyle =
        when (status) {
            JobStatus.PENDING_ACK -> StatusStyle(StatusGlyph.HOURGLASS, "PENDING ACK", 0xFF3A4654, 0xFFFFFFFF)
            JobStatus.UNCONFIRMED -> StatusStyle(StatusGlyph.BROKEN_LINK, "UNCONFIRMED", 0xFFFFD54F, 0xFF1A1200)
            JobStatus.NOT_RECEIVED -> StatusStyle(StatusGlyph.EXCLAMATION, "NOT RECEIVED", 0xFFFF8A65, 0xFF1F0A00)
            JobStatus.QUEUED -> StatusStyle(StatusGlyph.SQUARE, "QUEUED", 0xFF5C8DFF, 0xFF000000)
            JobStatus.ACQUIRING -> StatusStyle(StatusGlyph.CROSSHAIR, "ACQUIRING", 0xFF4DD0E1, 0xFF00181C)
            JobStatus.CAPTURING -> StatusStyle(StatusGlyph.DOT_PULSE, "CAPTURING", 0xFFFFFFFF, 0xFF101418)
            JobStatus.COMPLETED -> StatusStyle(StatusGlyph.CHECK, "COMPLETED", 0xFF2E9E6B, 0xFF000000)
            JobStatus.FAILED -> StatusStyle(StatusGlyph.CROSS, "FAILED", 0xFFE5484D, 0xFF000000)
            JobStatus.CANCELLED -> StatusStyle(StatusGlyph.SLASH, "CANCELLED", 0xFF5D6670, 0xFFFFFFFF)
            JobStatus.REJECTED -> StatusStyle(StatusGlyph.EXCLAMATION, "REJECTED", 0xFFE5484D, 0xFF000000)
        }

    public fun link(status: LinkStatus): StatusStyle =
        when (status) {
            LinkStatus.CONNECTED -> StatusStyle(StatusGlyph.FILLED_CIRCLE, "CONNECTED", 0xFF2E9E6B, 0xFF000000)
            LinkStatus.SYNCING -> StatusStyle(StatusGlyph.HOURGLASS, "SYNCING", 0xFFF2B33D, 0xFF1A1200)
            LinkStatus.CONNECTING -> StatusStyle(StatusGlyph.HOURGLASS, "CONNECTING", 0xFF5D6670, 0xFFFFFFFF)
            LinkStatus.OFFLINE -> StatusStyle(StatusGlyph.BROKEN_LINK, "LINK DOWN", 0xFFFF8A65, 0xFF1F0A00)
        }

    public fun feed(indicator: FeedIndicator): StatusStyle =
        when (indicator) {
            FeedIndicator.LIVE -> StatusStyle(StatusGlyph.FILLED_CIRCLE, "LIVE", 0xFFE5484D, 0xFF000000)
            FeedIndicator.SIMULATED -> StatusStyle(StatusGlyph.SQUARE, "SIMULATED", 0xFF5C8DFF, 0xFF000000)
            FeedIndicator.STALE -> StatusStyle(StatusGlyph.HOURGLASS, "NO VIDEO", 0xFF5D6670, 0xFFFFFFFF)
            FeedIndicator.DEGRADED -> StatusStyle(StatusGlyph.WARNING_TRIANGLE, "DEGRADED", 0xFFF2B33D, 0xFF1A1200)
            FeedIndicator.OFFLINE -> StatusStyle(StatusGlyph.BROKEN_LINK, "LINK DOWN", 0xFFFF8A65, 0xFF1F0A00)
        }
}

/** WCAG 2.x relative luminance / contrast helpers (also used by tests). */
public object Contrast {
    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    public fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    public fun ratio(
        a: Long,
        b: Long,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
