package com.protagonist.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import capture.domain.Modality
import capture.domain.SensorHealth
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** UI-level acceptance scenarios A, C, H plus branding. Layout/touch checks (F, G) are in LayoutAccessibilityTest. */
@RunWith(AndroidJUnit4::class)
class CaptureUiAcceptanceTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var h: UiHarness

    @After fun tearDown() {
        if (::h.isInitialized) h.close()
    }

    @Test fun scenarioA_selectionHighlightsEverywhere_addCreatesExactlyOneAckedJob() {
        h = UiHarness(rule).launch(1280.dp, 800.dp)
        rule.onNodeWithTag("track_box_S01").performClick()
        rule.waitUntil(5_000) { h.vm.uiState.value.selected?.label == "S01" }
        rule.onNodeWithTag("track_box_S01").assertIsSelected()
        rule.onNodeWithTag("candidate_S01").assertIsSelected()
        rule.onNodeWithTag("selected_label").assertTextEquals("S01")
        assertEquals(0, rule.onAllNodes(hasTestTagPrefix("queue_item_")).fetchSemanticsNodes().size, "selection must not queue")
        assertEquals(0, h.sim.server.enqueueFramesReceived)

        rule.onNodeWithTag("add_to_queue").performClick()
        rule.waitUntil(5_000) { h.vm.uiState.value.queue.any { it.jobId != null } }
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodes(hasTestTagPrefix("queue_item_")).fetchSemanticsNodes().size)
        assertEquals(1, h.sim.server.jobCount)
    }

    @Test fun scenarioC_modeSwitchKeepsIds() {
        h = UiHarness(rule).launch(1280.dp, 800.dp)
        rule.onNodeWithTag("candidate_S01").performClick()
        for (m in listOf(Modality.VISIBLE, Modality.THERMAL, Modality.FUSED)) {
            rule.onNodeWithTag("mode_${m.name}").performClick()
            rule.waitUntil(5_000) { h.vm.uiState.value.modality == m }
            rule.onNodeWithTag("track_box_S01").assertIsSelected()
        }
        h.sim.setCalibration(capture.domain.CalibrationStatus.UNAVAILABLE)
        h.waitForTag("overlay_warning")
    }

    @Test fun scenarioH_invalidSensorShowsDegradedAndNoLiveIndicator() {
        h = UiHarness(rule).launch(1280.dp, 800.dp)
        h.sim.setSensorHealth(Modality.THERMAL, SensorHealth.INVALID, "Simulated fault")
        rule.onNodeWithTag("mode_THERMAL").performClick()
        rule.waitUntil(5_000) {
            rule.onNodeWithTag("feed_indicator").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription) ==
                listOf("Feed DEGRADED")
        }
        val descriptions =
            rule.onRoot(useUnmergedTree = true).fetchSemanticsNode().let { root ->
                generateSequence(listOf(root)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }
                    .flatten()
                    .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription) }
                    .flatten()
                    .toList()
            }
        assertTrue(descriptions.none { it == "Feed LIVE" || it == "Feed SIMULATED" }, "no misleading live indicator")

        h.sim.setSensorHealth(Modality.VISIBLE, SensorHealth.INVALID, "Simulated fault")
        rule.onNodeWithTag("candidate_S02").performClick()
        rule.waitUntil(5_000) { !h.vm.uiState.value.enqueue.enabled && h.vm.uiState.value.selected != null }
        rule.onNodeWithTag("add_to_queue").assertIsNotEnabled()
    }

    @Test fun branding_headerAndLauncherLabelAreProtagonist() {
        h = UiHarness(rule).launch()
        rule.onNodeWithTag("app_header").assertTextEquals("Protagonist")
        rule.onNodeWithTag("tab_capture").assertExists()
        rule.onNodeWithTag("tab_settings").assertExists()
        rule.onNodeWithTag("tab_system_status").assertExists()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("Protagonist", ctx.getString(R.string.app_name))
        assertEquals("Protagonist", ctx.applicationInfo.loadLabel(ctx.packageManager).toString())
        assertTrue(rule.onAllNodesWithTag("simulated_banner").fetchSemanticsNodes().isNotEmpty(), "simulator is labelled as such")
    }
}
