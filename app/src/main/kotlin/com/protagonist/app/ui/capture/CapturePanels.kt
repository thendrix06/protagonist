package com.protagonist.app.ui.capture

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import capture.designsystem.ControlColors
import capture.designsystem.ControlDimens
import capture.designsystem.StatusChip
import capture.designsystem.asColor
import capture.domain.Modality
import capture.domain.SectorId
import capture.presentation.AlertSeverity
import capture.presentation.AlertUi
import capture.presentation.CaptureUiState
import capture.presentation.EnqueueUi
import capture.presentation.Labels
import capture.presentation.QueueItemUi
import capture.presentation.SectorUi
import capture.presentation.SelectedSubjectUi
import capture.presentation.StatusTokens
import capture.presentation.SubjectUi
import com.protagonist.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeToggle(
    current: capture.domain.Modality,
    pending: capture.domain.Modality?,
    onSelect: (Modality) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.testTag("mode_toggle")) {
        Modality.entries.forEachIndexed { index, m ->
            val isPending = m == pending
            SegmentedButton(
                selected = m == current,
                onClick = { onSelect(m) },
                shape = SegmentedButtonDefaults.itemShape(index, Modality.entries.size),
                modifier =
                    Modifier
                        .heightIn(min = ControlDimens.MinTouchTarget)
                        .semantics { if (isPending) stateDescription = "Requested, awaiting backend" }
                        .testTag("mode_${m.name}"),
                label = { Text(Labels.modality(m) + if (isPending) "…" else "", maxLines = 1) },
            )
        }
    }
}

@Composable
fun SectorSelector(
    sectors: List<SectorUi>,
    active: SectorId?,
    onSelect: (SectorId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val label = sectors.firstOrNull { it.id == active }?.label ?: "—"
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            modifier =
                Modifier
                    .heightIn(min = ControlDimens.MinTouchTarget)
                    .semantics { contentDescription = "Sector: $label. Change sector" }
                    .testTag("sector_selector"),
        ) { Text("${stringResource(R.string.sector)}: $label", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sectors.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label) },
                    onClick = {
                        open = false
                        onSelect(s.id)
                    },
                    modifier = Modifier.testTag("sector_option_${s.id.value}"),
                )
            }
        }
    }
}

@Composable
fun SelectedSubjectCard(
    selected: SelectedSubjectUi?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("selected_panel"),
        color = ControlColors.SurfaceRaised,
        shape = RoundedCornerShape(8.dp),
        border =
            BorderStroke(
                if (selected !=
                    null
                ) {
                    2.dp
                } else {
                    1.dp
                },
                if (selected != null) ControlColors.Selection else ControlColors.Outline,
            ),
    ) {
        if (selected == null) {
            Text(stringResource(R.string.selected_none), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            return@Surface
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    selected.label,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("selected_label"),
                )
                StatusChip(StatusTokens.track(selected.state), spokenPrefix = "State")
                selected.queueStatus?.let { StatusChip(StatusTokens.job(it), spokenPrefix = "Job") }
            }
            selected.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ControlColors.Warning) }
            if (!compact) {
                Text(
                    "Observed in: " + selected.observedIn.joinToString { Labels.modality(it) }.ifEmpty { "none reported" },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.selection_hint), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun AddToQueueButton(
    enqueue: EnqueueUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Button(
            onClick = onClick,
            enabled = enqueue.enabled,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(ControlDimens.PrimaryActionHeight)
                    .semantics {
                        contentDescription =
                            if (enqueue.enabled) {
                                "Add to queue: capture up to ${enqueue.burstLimit} frames within ${enqueue.ttlMs / 1000} seconds"
                            } else {
                                "Add to queue unavailable: ${enqueue.blockedReason}"
                            }
                    }.testTag("add_to_queue"),
            colors = ButtonDefaults.buttonColors(containerColor = ControlColors.Primary, contentColor = ControlColors.OnPrimary),
        ) {
            Text(stringResource(R.string.add_to_queue), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = enqueue.blockedReason ?: "Burst ≤ ${enqueue.burstLimit} frames · TTL ${enqueue.ttlMs / 1000}s",
            style = MaterialTheme.typography.labelSmall,
            color = if (enqueue.enabled) ControlColors.OnSurface else ControlColors.Warning,
            modifier =
                Modifier
                    .padding(top = 2.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("enqueue_reason"),
        )
    }
}

@Composable
fun QueueRow(
    item: QueueItemUi,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = ControlDimens.MinTouchTarget)
            .semantics(mergeDescendants = true) { contentDescription = item.contentDescription }
            .testTag("queue_item_${item.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(item.trackLabel, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
        StatusChip(StatusTokens.job(item.status), text = item.statusLabel.uppercase())
        Column(Modifier.weight(1f)) {
            item.progress?.let { Text("$it frames", style = MaterialTheme.typography.labelSmall) }
            item.reason?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            item.jobId?.let {
                Text(it.value, style = MaterialTheme.typography.labelSmall, color = ControlColors.OnSurface.copy(alpha = 0.7f))
            }
        }
        if (item.cancellable) {
            TextButton(
                onClick = onCancel,
                modifier =
                    Modifier
                        .heightIn(min = ControlDimens.MinTouchTarget)
                        .semantics { contentDescription = "Cancel job for ${item.trackLabel}" }
                        .testTag("cancel_${item.key}"),
            ) { Text(stringResource(R.string.cancel_job)) }
        }
    }
}

/** Queue rendered as a plain Column (bounded history) so it can live inside scrollable phone layouts. */
@Composable
fun QueueList(
    queue: List<QueueItemUi>,
    onCancel: (QueueItemUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag("queue_panel")) {
        if (queue.isEmpty()) {
            Text(
                stringResource(R.string.queue_empty),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        // Newest first so active work stays at the top.
        queue.asReversed().forEach { item -> QueueRow(item, onCancel = { onCancel(item) }) }
    }
}

@Composable
fun CandidateStrip(
    subjects: List<SubjectUi>,
    onSubjectTapped: (SubjectUi) -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 104.dp,
) {
    LazyRow(
        modifier.testTag("candidate_strip"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(subjects, key = { it.trackId.value }) { s -> CandidateCard(s, { onSubjectTapped(s) }, Modifier.width(cardWidth)) }
    }
}

@Composable
fun CandidateCard(
    subject: SubjectUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = StatusTokens.track(subject.state)
    Column(
        modifier
            .border(
                if (subject.selected) ControlDimens.SelectionStroke else 1.dp,
                if (subject.selected) ControlColors.Selection else ControlColors.Outline,
                RoundedCornerShape(8.dp),
            ).background(ControlColors.SurfaceRaised, RoundedCornerShape(8.dp))
            .semantics {
                contentDescription = subject.contentDescription
                selected = subject.selected
            }.clickable(role = Role.Button, onClickLabel = "Highlight ${subject.label}", onClick = onClick)
            .padding(6.dp)
            .testTag("candidate_${subject.label}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Simulated preview tile: a synthetic silhouette, never real imagery from the simulator.
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF2A3440), style.containerArgb.asColor().copy(alpha = 0.6f))),
                    RoundedCornerShape(4.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (subject.previewKey == null) {
                Text("no preview", style = MaterialTheme.typography.labelSmall)
            } else {
                Canvas(Modifier.size(20.dp, 40.dp)) {
                    drawCircle(Color.White.copy(alpha = 0.6f), size.width * 0.3f, center.copy(y = size.height * 0.18f))
                    drawRoundRect(
                        Color.White.copy(alpha = 0.6f),
                        topLeft = center.copy(x = size.width * 0.2f, y = size.height * 0.38f),
                        size = size.copy(width = size.width * 0.6f, height = size.height * 0.6f),
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(subject.label, fontWeight = FontWeight.Bold)
            subject.queueStatus?.let {
                capture.designsystem.GlyphIcon(StatusTokens.job(it).glyph, StatusTokens.job(it).containerArgb.asColor(), 12.dp)
            }
        }
        StatusChip(style, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun AlertsBar(
    alerts: List<AlertUi>,
    feedback: String?,
    onDismissFeedback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (alerts.isEmpty() && feedback == null) return
    Column(modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("alerts"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        alerts.forEach { a ->
            val bg = if (a.severity == AlertSeverity.CRITICAL) ControlColors.Critical else ControlColors.Warning
            Text(
                a.text,
                color = Color.Black,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(4.dp)).padding(6.dp),
            )
        }
        feedback?.let {
            Row(
                Modifier.fillMaxWidth().background(ControlColors.SurfaceRaised, RoundedCornerShape(4.dp)).padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).testTag("feedback"))
                TextButton(onClick = onDismissFeedback, modifier = Modifier.heightIn(min = ControlDimens.MinTouchTarget)) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
fun ControlBar(
    ui: CaptureUiState,
    onMode: (Modality) -> Unit,
    onSector: (SectorId) -> Unit,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
) {
    if (stacked) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ModeToggle(ui.modality, ui.pendingModality, onMode, Modifier.fillMaxWidth())
            SectorSelector(ui.sectors, ui.activeSector, onSector, Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModeToggle(ui.modality, ui.pendingModality, onMode, Modifier.weight(1f))
            SectorSelector(ui.sectors, ui.activeSector, onSector)
        }
    }
}

@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = modifier.padding(vertical = 4.dp))
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun FillBox(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize()) { content() }
