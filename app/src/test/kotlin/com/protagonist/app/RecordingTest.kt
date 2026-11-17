package com.protagonist.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import capture.domain.CalibrationStatus
import capture.domain.JobStatus
import capture.domain.LinkStatus
import capture.domain.Modality
import capture.domain.SensorHealth
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Records a scripted demo of the real app (simulator backend) as a frame sequence plus a manifest of
 * (frame, elapsed ms, caption). `scripts/make_recording.sh` turns it into an MP4.
 *
 * Opt-in: ./gradlew :app:testDebugUnitTest --tests '*RecordingTest*' -Pprotagonist.recording=true
 *
 * This is a Robolectric rendering of the app's own UI, not a capture of a physical or emulated device screen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordingTest {
    @get:Rule val rule = createComposeRule()
    private var h: UiHarness? = null
    private lateinit var dir: File
    private val manifest = StringBuilder()
    private var frame = 0
    private var start = 0L

    @Before fun optIn() {
        val path = System.getProperty("protagonist.recording.dir")
        assumeTrue(path != null)
        dir = File(path!!)
    }

    @After fun tearDown() {
        h?.close()
    }

    private fun begin(name: String): UiHarness {
        dir =
            File(dir, name).apply {
                deleteRecursively()
                mkdirs()
            }
        val harness = UiHarness(rule).launch().also { h = it }
        start = System.currentTimeMillis()
        return harness
    }

    private fun capture(caption: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File(dir, "f%05d.png".format(frame++))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        manifest.append("${file.name}\t${System.currentTimeMillis() - start}\t$caption\n")
    }

    /** Keep capturing for [ms] of wall-clock time; the simulator keeps running meanwhile. */
    private fun hold(
        ms: Long,
        caption: String,
    ) {
        val until = System.currentTimeMillis() + ms
        do capture(caption) while (System.currentTimeMillis() < until)
    }

    private fun holdUntil(
        caption: String,
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        val until = System.currentTimeMillis() + timeoutMs
        do capture(caption) while (!condition() && System.currentTimeMillis() < until)
    }

    private fun finish() {
        File(dir, "manifest.tsv").writeText(manifest.toString())
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun tabletDemo() {
        val h = begin("tablet")
        val ui = { h.vm.uiState.value }
        hold(2_500, "Simulator connected: subjects S01–S04 tracked (simulated scene)")

        rule.onNodeWithTag("track_box_S01").performClick()
        hold(2_500, "Tap S01: highlight only. Panorama, strip and panel agree; queue unchanged")

        rule.onNodeWithTag("add_to_queue").performClick()
        holdUntil("ADD TO QUEUE: PENDING ACK until backend acknowledges") { ui().queue.firstOrNull()?.jobId != null }
        holdUntil("Backend assigns J-0001; status follows backend reports") { ui().queue.firstOrNull()?.status == JobStatus.CAPTURING }
        hold(1_500, "Capturing: frame count is what the backend reports")

        for (id in listOf("S02", "S03", "S04")) {
            rule.onNodeWithTag("candidate_$id").performClick()
            rule.onNodeWithTag("add_to_queue").performClick()
        }
        hold(3_000, "Rapid authorization of S02–S04: one job each, statuses update asynchronously")
        rule.onNodeWithTag("candidate_S01").performClick()
        holdUntil("Selection continues while the backend works through the queue", timeoutMs = 15_000) {
            ui().queue.all { it.terminal }
        }
        hold(1_500, "All four jobs completed with backend-reported frame counts")

        rule.onNodeWithTag("mode_THERMAL").performClick()
        hold(2_000, "Thermal: same track IDs across modes")
        rule.onNodeWithTag("mode_FUSED").performClick()
        hold(2_000, "Fused: calibrated overlay")
        h.sim.setCalibration(CalibrationStatus.UNAVAILABLE)
        hold(2_500, "Calibration lost: overlay marked approximate (dashed)")
        h.sim.setCalibration(CalibrationStatus.CALIBRATED)

        h.sim.setSensorHealth(Modality.THERMAL, SensorHealth.INVALID, "Simulated fault")
        rule.onNodeWithTag("mode_THERMAL").performClick()
        hold(2_500, "Thermal sensor invalid: DEGRADED, no live indicator")
        h.sim.setSensorHealth(Modality.THERMAL, SensorHealth.OK)
        rule.onNodeWithTag("mode_VISIBLE").performClick()
        hold(1_000, "Sensor restored, back to Visible")

        rule.onNodeWithTag("candidate_S02").performClick()
        holdUntil("Select S02") { ui().enqueue.enabled }
        h.sim.dropLinkAfterNextCommand()
        rule.onNodeWithTag("add_to_queue").performClick()
        holdUntil("Link drops right after the command: UNCONFIRMED, new captures disabled") {
            ui().link.status == LinkStatus.OFFLINE
        }
        hold(2_500, "Link down: UNCONFIRMED; nothing is resent")
        h.sim.restoreLink()
        holdUntil("Reconnect: snapshot requested") { ui().link.status == LinkStatus.CONNECTED }
        hold(3_000, "Reconciled by idempotency key to the backend's job; no duplicate enqueue")
        finish()
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-port-hdpi")
    fun phoneDemo() {
        val h = begin("phone")
        val ui = { h.vm.uiState.value }
        hold(2_000, "Phone portrait")
        rule.onNodeWithTag("candidate_S01").performClick()
        hold(2_000, "Tap S01: highlight only")
        rule.onNodeWithTag("add_to_queue").performClick()
        holdUntil("ADD TO QUEUE: pending until acknowledged") { ui().queue.firstOrNull()?.jobId != null }
        holdUntil("Acquiring / capturing (backend-reported)", timeoutMs = 8_000) { ui().queue.firstOrNull()?.terminal == true }
        hold(1_500, "Completed")
        finish()
    }
}
