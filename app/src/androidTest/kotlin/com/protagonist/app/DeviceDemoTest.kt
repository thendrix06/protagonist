package com.protagonist.app

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import capture.domain.CalibrationStatus
import capture.domain.JobStatus
import capture.domain.LinkStatus
import capture.domain.Modality
import capture.domain.SensorHealth
import capture.sim.SimulatorControls
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scripted demo of the real app (MainActivity + simulator) for the CI emulator screen recording.
 * Skipped unless the instrumentation argument `protagonist.demo=true` is passed (see scripts/ci_instrumented.sh).
 *
 * Timeline (approx.): highlight S01 → authorize → rapid S02–S04 → modes + uncalibrated overlay →
 * DEGRADED thermal → link loss right after a command → reconnect and reconciliation.
 */
@RunWith(AndroidJUnit4::class)
class DeviceDemoTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun optIn() = assumeTrue(InstrumentationRegistry.getArguments().getString("protagonist.demo") == "true")

    private val container get() = (rule.activity.application as ProtagonistApplication).container
    private val sim: SimulatorControls get() = container.simulatorControls!!
    private val ui get() = container.stateHolder.uiState.value

    private fun pause(ms: Long) {
        rule.waitForIdle()
        Thread.sleep(ms)
    }

    private fun waitFor(
        timeoutMs: Long = 15_000,
        condition: () -> Boolean,
    ) = rule.waitUntil(timeoutMs) { condition() }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performClick()
    }

    @Test fun demo() {
        waitFor { ui.subjects.size >= 4 }
        pause(3_000)

        tap("candidate_S01")
        pause(2_500)

        tap("add_to_queue")
        waitFor { ui.queue.firstOrNull()?.status == JobStatus.CAPTURING }
        pause(1_500)

        for (id in listOf("S02", "S03", "S04")) {
            tap("candidate_$id")
            tap("add_to_queue")
            pause(400)
        }
        tap("candidate_S01")
        waitFor(30_000) { ui.queue.size == 4 && ui.queue.all { it.terminal } }
        pause(2_000)

        tap("mode_THERMAL")
        pause(2_000)
        tap("mode_FUSED")
        pause(2_000)
        sim.setCalibration(CalibrationStatus.UNAVAILABLE)
        pause(3_000)
        sim.setCalibration(CalibrationStatus.CALIBRATED)

        sim.setSensorHealth(Modality.THERMAL, SensorHealth.INVALID, "Simulated fault")
        tap("mode_THERMAL")
        pause(3_000)
        sim.setSensorHealth(Modality.THERMAL, SensorHealth.OK)
        tap("mode_VISIBLE")
        pause(1_500)

        tap("candidate_S02")
        waitFor { ui.enqueue.enabled }
        sim.dropLinkAfterNextCommand()
        tap("add_to_queue")
        waitFor { ui.link.status == LinkStatus.OFFLINE }
        pause(3_500)
        sim.restoreLink()
        waitFor { ui.link.status == LinkStatus.CONNECTED }
        pause(4_000)

        tap("tab_system_status")
        pause(3_000)
        tap("tab_capture")
        pause(1_500)
    }
}
