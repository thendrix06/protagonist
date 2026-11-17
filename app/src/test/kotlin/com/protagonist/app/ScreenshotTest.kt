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
 * Renders documentation screenshots of real app states (simulator backend) into docs/screens/.
 * Opt-in: ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*' -Pprotagonist.screenshots=true
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private var h: UiHarness? = null

    @Before fun optIn() = assumeTrue(System.getProperty("protagonist.screenshots.dir") != null)

    @After fun tearDown() {
        h?.close()
    }

    private fun save(name: String) {
        rule.waitForIdle()
        val dir = File(System.getProperty("protagonist.screenshots.dir")!!).apply { mkdirs() }
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun launch(): UiHarness = UiHarness(rule).launch().also { h = it }

    private fun waitFor(condition: () -> Boolean) = rule.waitUntil(10_000) { condition() }

    @Test
    @Config(qualifiers = TABLET)
    fun tablet_01_selected() {
        val h = launch()
        rule.onNodeWithTag("candidate_S01").performClick()
        waitFor { h.vm.uiState.value.selected?.label == "S01" }
        save("tablet-01-capture-selected")
    }

    @Test
    @Config(qualifiers = TABLET)
    fun tablet_02_rapid_queue() {
        val h = launch()
        for (id in listOf("S01", "S02", "S03", "S04")) {
            rule.onNodeWithTag("candidate_$id").performClick()
            rule.onNodeWithTag("add_to_queue").performClick()
        }
        waitFor { h.vm.uiState.value.queue.size == 4 && h.vm.uiState.value.queue.any { it.status == JobStatus.CAPTURING } }
        save("tablet-02-rapid-queue")
    }

    @Test
    @Config(qualifiers = TABLET)
    fun tablet_03_thermal_degraded() {
        val h = launch()
        h.sim.setSensorHealth(Modality.THERMAL, SensorHealth.INVALID, "Simulated fault")
        rule.onNodeWithTag("mode_THERMAL").performClick()
        waitFor { h.vm.uiState.value.modality == Modality.THERMAL && h.vm.uiState.value.alerts.isNotEmpty() }
        save("tablet-03-thermal-degraded")
    }

    @Test
    @Config(qualifiers = TABLET)
    fun tablet_04_fused_uncalibrated() {
        val h = launch()
        h.sim.setCalibration(CalibrationStatus.UNAVAILABLE)
        rule.onNodeWithTag("mode_FUSED").performClick()
        rule.onNodeWithTag("candidate_S02").performClick()
        waitFor { h.vm.uiState.value.overlay.warning != null }
        save("tablet-04-fused-uncalibrated")
    }

    @Test
    @Config(qualifiers = TABLET)
    fun tablet_05_link_down_unconfirmed() {
        val h = launch()
        rule.onNodeWithTag("candidate_S01").performClick()
        waitFor { h.vm.uiState.value.enqueue.enabled }
        h.sim.dropLinkAfterNextCommand()
        rule.onNodeWithTag("add_to_queue").performClick()
        waitFor { h.vm.uiState.value.link.status == LinkStatus.OFFLINE && h.vm.uiState.value.queue.isNotEmpty() }
        save("tablet-05-link-down-unconfirmed")
        h.sim.restoreLink()
        waitFor { h.vm.uiState.value.queue.firstOrNull()?.jobId != null }
        save("tablet-06-reconnected-reconciled")
    }

    @Test
    @Config(qualifiers = TABLET)
    fun tablet_07_settings_and_status() {
        val h = launch()
        rule.onNodeWithTag("tab_settings").performClick()
        save("tablet-07-settings")
        rule.onNodeWithTag("tab_system_status").performClick()
        waitFor { h.vm.systemStatus.value != null }
        save("tablet-08-system-status")
    }

    @Test
    @Config(qualifiers = PHONE_PORTRAIT)
    fun phone_01_portrait() {
        val h = launch()
        rule.onNodeWithTag("candidate_S01").performClick()
        rule.onNodeWithTag("add_to_queue").performClick()
        waitFor { h.vm.uiState.value.queue.firstOrNull()?.jobId != null }
        save("phone-01-portrait")
    }

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun phone_02_landscape() {
        val h = launch()
        rule.onNodeWithTag("candidate_S02").performClick()
        waitFor { h.vm.uiState.value.selected?.label == "S02" }
        save("phone-02-landscape")
    }

    private companion object {
        const val TABLET = "w1280dp-h800dp-land-hdpi"
        const val PHONE_PORTRAIT = "w360dp-h780dp-port-xhdpi"
        const val PHONE_LANDSCAPE = "w780dp-h360dp-land-xhdpi"
    }
}
