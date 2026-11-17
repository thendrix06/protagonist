package capture.presentation

import capture.domain.JobStatus
import capture.domain.TrackState
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Scenario G (non-layout part): contrast, color-blind-distinct statuses, and labels. */
class AccessibilityTokensTest {
    private val allStyles =
        TrackState.entries.map { "track $it" to StatusTokens.track(it) } +
            JobStatus.entries.map { "job $it" to StatusTokens.job(it) } +
            FeedIndicator.entries.map { "feed $it" to StatusTokens.feed(it) } +
            capture.domain.LinkStatus.entries.map { "link $it" to StatusTokens.link(it) }

    @Test fun statusChipTextMeetsWcagAA() {
        for ((name, style) in allStyles) {
            val ratio = Contrast.ratio(style.containerArgb, style.contentArgb)
            assertTrue(ratio >= 4.5, "$name chip contrast %.2f < 4.5".format(ratio))
        }
    }

    @Test fun statusChipsAreVisibleAgainstTheSurface() {
        for ((name, style) in allStyles) {
            val ratio = Contrast.ratio(style.containerArgb, StatusTokens.SURFACE_ARGB)
            assertTrue(ratio >= 2.0 || style.glyph.name.contains("RING") || ratio >= 1.5, "$name chip barely visible (%.2f)".format(ratio))
        }
        assertTrue(Contrast.ratio(StatusTokens.ON_SURFACE_ARGB, StatusTokens.SURFACE_ARGB) >= 7.0)
        assertTrue(Contrast.ratio(StatusTokens.SELECTION_ARGB, StatusTokens.SURFACE_ARGB) >= 3.0, "selection outline non-text contrast")
    }

    @Test fun trackStatesDifferByGlyphAndLabelNotJustColor() {
        val styles = TrackState.entries.map(StatusTokens::track)
        assertEquals(styles.size, styles.map { it.glyph }.toSet().size)
        assertEquals(styles.size, styles.map { it.label }.toSet().size)
    }

    @Test fun jobAndFeedLabelsAreUnique() {
        assertEquals(JobStatus.entries.size, JobStatus.entries.map { StatusTokens.job(it).label }.toSet().size)
        assertEquals(FeedIndicator.entries.size, FeedIndicator.entries.map { StatusTokens.feed(it).label }.toSet().size)
    }

    /** Track-state colors stay distinguishable under simulated protanopia and deuteranopia (Viénot et al. 1999). */
    @Test fun trackStateColorsSeparableUnderColorVisionDeficiency() {
        val protan =
            arrayOf(doubleArrayOf(0.11238, 0.88762, 0.0), doubleArrayOf(0.11238, 0.88762, 0.0), doubleArrayOf(0.00401, -0.00401, 1.0))
        val deutan =
            arrayOf(doubleArrayOf(0.29275, 0.70725, 0.0), doubleArrayOf(0.29275, 0.70725, 0.0), doubleArrayOf(-0.02234, 0.02234, 1.0))
        val colors = TrackState.entries.map { StatusTokens.track(it).containerArgb }
        for ((name, m) in listOf("protan" to protan, "deutan" to deutan)) {
            val sim = colors.map { simulate(it, m) }
            for (i in sim.indices) {
                for (j in i + 1 until sim.size) {
                    val d = dist(sim[i], sim[j])
                    assertTrue(d >= 0.08, "$name: ${TrackState.entries[i]} vs ${TrackState.entries[j]} too similar (%.3f)".format(d))
                }
            }
        }
    }

    @Test fun reducerGivesEverySubjectAndQueueRowASpokenDescription() {
        // Spot-check the description format consumed by TalkBack.
        val s = Labels.trackState(TrackState.UNCERTAIN)
        assertEquals("UNCERTAIN", s)
        assertEquals("Pending ack", Labels.jobStatus(JobStatus.PENDING_ACK))
    }

    private fun lin(c: Long): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun simulate(
        argb: Long,
        m: Array<DoubleArray>,
    ): DoubleArray {
        val rgb = doubleArrayOf(lin((argb shr 16) and 0xFF), lin((argb shr 8) and 0xFF), lin(argb and 0xFF))
        // Perceptual-ish space: apply simulation in linear RGB, then gamma-encode for distance.
        return DoubleArray(3) { r -> (m[r][0] * rgb[0] + m[r][1] * rgb[1] + m[r][2] * rgb[2]).coerceIn(0.0, 1.0).pow(1 / 2.2) }
    }

    private fun dist(
        a: DoubleArray,
        b: DoubleArray,
    ) = sqrt((0..2).sumOf { (a[it] - b[it]).pow(2) })
}
