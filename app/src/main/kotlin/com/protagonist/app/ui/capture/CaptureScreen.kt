package com.protagonist.app.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import capture.designsystem.ControlDimens
import capture.presentation.CaptureUiState
import com.protagonist.app.CaptureViewModel
import com.protagonist.app.R

/** Which arrangement the Capture tab uses. Exposed for layout tests (scenario F). */
enum class CaptureLayout { TABLET, COMPACT_LANDSCAPE, PHONE_PORTRAIT }

fun captureLayoutFor(
    widthDp: Int,
    heightDp: Int,
): CaptureLayout =
    when {
        widthDp >= 600 && widthDp > heightDp && heightDp >= 480 -> CaptureLayout.TABLET
        widthDp > heightDp -> CaptureLayout.COMPACT_LANDSCAPE
        widthDp >= 840 -> CaptureLayout.TABLET
        else -> CaptureLayout.PHONE_PORTRAIT
    }

@Composable
fun CaptureScreen(
    ui: CaptureUiState,
    vm: CaptureViewModel,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        when (captureLayoutFor(maxWidth.value.toInt(), maxHeight.value.toInt())) {
            CaptureLayout.TABLET -> TabletCapture(ui, vm, panelWidth = if (maxWidth >= 1000.dp) 360.dp else 300.dp)
            CaptureLayout.COMPACT_LANDSCAPE -> CompactLandscapeCapture(ui, vm)
            CaptureLayout.PHONE_PORTRAIT -> PhonePortraitCapture(ui, vm)
        }
    }
}

/**
 * Tablet landscape (primary): maximal observation view; upper-right panel with selected subject, prominent
 * ADD TO QUEUE and compact queue; candidate strip along the bottom.
 */
@Composable
private fun TabletCapture(
    ui: CaptureUiState,
    vm: CaptureViewModel,
    panelWidth: androidx.compose.ui.unit.Dp,
) {
    Column(Modifier.fillMaxSize().padding(8.dp).testTag("layout_tablet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ControlBar(ui, vm::onModeSelected, vm::onSectorSelected, Modifier.fillMaxWidth())
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ObservationView(ui, { vm.onSubjectTapped(it.trackId) }, Modifier.weight(1f).fillMaxHeight())
            Column(
                Modifier.width(panelWidth).fillMaxHeight().verticalScroll(rememberScrollState()).testTag("right_panel"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SelectedSubjectCard(ui.selected)
                AddToQueueButton(ui.enqueue, vm::onAddToQueue)
                AlertsBar(ui.alerts, ui.feedback, vm::onDismissFeedback)
                SectionTitle(stringResource(R.string.queue_title) + " (${ui.queue.count { !it.terminal }} active)")
                QueueList(ui.queue, { it.jobId?.let(vm::onCancel) })
            }
        }
        CandidateStrip(ui.subjects, { vm.onSubjectTapped(it.trackId) }, Modifier.fillMaxWidth().height(124.dp))
    }
}

/** Phone landscape / short screens: view + strip on the left, controls column on the right. */
@Composable
private fun CompactLandscapeCapture(
    ui: CaptureUiState,
    vm: CaptureViewModel,
) {
    Row(Modifier.fillMaxSize().padding(6.dp).testTag("layout_compact_landscape"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ObservationView(ui, { vm.onSubjectTapped(it.trackId) }, Modifier.weight(1f).fillMaxWidth())
            CandidateStrip(ui.subjects, { vm.onSubjectTapped(it.trackId) }, Modifier.fillMaxWidth().height(118.dp))
        }
        Column(
            Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState()).testTag("right_panel"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ADD TO QUEUE stays at the top of the column: always within thumb reach without scrolling.
            SelectedSubjectCard(ui.selected, compact = true)
            AddToQueueButton(ui.enqueue, vm::onAddToQueue)
            ControlBar(ui, vm::onModeSelected, vm::onSectorSelected, stacked = true, modeColumns = 2)
            AlertsBar(ui.alerts, ui.feedback, vm::onDismissFeedback)
            CollapsibleQueue(ui, vm)
        }
    }
}

/**
 * Phone portrait: view on top, then selected card and large ADD TO QUEUE, a collapsible queue, and the
 * horizontally scrolling gallery below.
 */
@Composable
private fun PhonePortraitCapture(
    ui: CaptureUiState,
    vm: CaptureViewModel,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp).testTag("layout_phone"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ControlBar(ui, vm::onModeSelected, vm::onSectorSelected, Modifier.fillMaxWidth(), stacked = true)
        ObservationView(ui, { vm.onSubjectTapped(it.trackId) }, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
        SelectedSubjectCard(ui.selected, compact = true)
        AddToQueueButton(ui.enqueue, vm::onAddToQueue)
        AlertsBar(ui.alerts, ui.feedback, vm::onDismissFeedback)
        CollapsibleQueue(ui, vm)
        CandidateStrip(ui.subjects, { vm.onSubjectTapped(it.trackId) }, Modifier.fillMaxWidth().height(124.dp))
    }
}

@Composable
private fun CollapsibleQueue(
    ui: CaptureUiState,
    vm: CaptureViewModel,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val active = ui.queue.count { !it.terminal }
    TextButton(
        onClick = { expanded = !expanded },
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ControlDimens.MinTouchTarget)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .testTag("queue_toggle"),
    ) { Text(stringResource(R.string.queue_title) + " ($active active, ${ui.queue.size} total) " + if (expanded) "▲" else "▼") }
    if (expanded) QueueList(ui.queue, { it.jobId?.let(vm::onCancel) })
}
