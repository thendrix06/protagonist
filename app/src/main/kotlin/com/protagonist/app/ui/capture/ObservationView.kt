package com.protagonist.app.ui.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capture.designsystem.ControlColors
import capture.designsystem.ControlDimens
import capture.designsystem.StatusChip
import capture.designsystem.asColor
import capture.domain.Modality
import capture.domain.TrackState
import capture.presentation.CaptureUiState
import capture.presentation.FeedIndicator
import capture.presentation.StatusTokens
import capture.presentation.SubjectUi
import com.protagonist.app.R
import kotlin.math.sin

/**
 * The single wide observation view. Draws the (simulated) panorama for the current modality and overlays each
 * backend-reported track as a box labelled with its ID. Tapping a box ONLY highlights it.
 *
 * Each overlay box is a real composable with semantics and a >= 48dp hit target, so TalkBack users and tests can
 * reach subjects individually (a bare Canvas would not be accessible).
 */
@Composable
fun ObservationView(
    ui: CaptureUiState,
    onSubjectTapped: (SubjectUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .clipToBounds()
            .background(Color.Black)
            .semantics { contentDescription = "Observation view, ${ui.modality.name.lowercase()} mode, ${ui.feed.label}" }
            .testTag("observation_view"),
    ) {
        val w = maxWidth
        val h = maxHeight
        val simulated = ui.feed.indicator == FeedIndicator.SIMULATED || ui.link.simulated
        Canvas(Modifier.fillMaxSize()) { drawScene(ui.modality, ui.sceneTimeMs, ui.feed.indicator) }

        if (ui.overlay.visible) {
            ui.subjects.filter { it.box != null }.forEach { s ->
                val box = s.box!!
                val style = StatusTokens.track(s.state)
                val strokeColor = if (s.selected) ControlColors.Selection else style.containerArgb.asColor()
                val boxW = (w * box.width).coerceAtLeast(ControlDimens.MinTouchTarget)
                val boxH = (h * box.height).coerceAtLeast(ControlDimens.MinTouchTarget)
                val left = w * box.centerX - boxW / 2
                val top = h * box.centerY - boxH / 2
                Box(
                    Modifier
                        .offset(left, top)
                        .size(boxW, boxH)
                        .border(
                            width = if (s.selected) ControlDimens.SelectionStroke else 2.dp,
                            color = strokeColor,
                            shape = RoundedCornerShape(4.dp),
                        ).semantics {
                            contentDescription = s.contentDescription
                            stateDescription = s.stateLabel
                            selected = s.selected
                        }.clickable(role = Role.Button, onClickLabel = "Highlight ${s.label}") { onSubjectTapped(s) }
                        .testTag("track_box_${s.label}"),
                ) {
                    if (!ui.overlay.calibrated) {
                        // Approximate overlay: dashed inner outline in addition to the banner.
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(
                                Color.White.copy(alpha = 0.7f),
                                style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                            )
                        }
                    }
                }
                // ID label above the box, same text as in the strip, panel and queue.
                Text(
                    text = s.label + if (s.state != TrackState.ACTIVE) " · ${s.stateLabel}" else "",
                    color = if (s.selected) Color.Black else style.contentArgb.asColor(),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                    modifier =
                        Modifier
                            .offset(left, (top - 22.dp).coerceAtLeast(0.dp))
                            .background(
                                if (s.selected) ControlColors.Selection else style.containerArgb.asColor(),
                                RoundedCornerShape(4.dp),
                            ).padding(horizontal = 4.dp),
                )
            }
        }

        Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
            StatusChip(
                StatusTokens.feed(ui.feed.indicator),
                text = ui.feed.label,
                spokenPrefix = "Feed",
                modifier = Modifier.testTag("feed_indicator"),
            )
            ui.feed.detail?.takeIf { ui.feed.indicator != FeedIndicator.SIMULATED }?.let {
                Text(it, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (simulated) {
            Text(
                stringResource(R.string.simulated_scene),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).testTag("simulated_banner"),
            )
        }
        ui.overlay.warning?.let {
            Text(
                it,
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(ControlColors.Warning, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("overlay_warning"),
            )
        }
        if (ui.subjects.isEmpty()) {
            Text("No subjects reported in this sector", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/** Procedural, clearly synthetic panorama. Never presented as real imagery. */
private fun DrawScope.drawScene(
    modality: Modality,
    sceneTimeMs: Long,
    feed: FeedIndicator,
) {
    val (sky, ground) =
        when (modality) {
            Modality.VISIBLE -> Color(0xFF4F6D8A) to Color(0xFF3B4A35)
            Modality.LOW_LIGHT -> Color(0xFF0E2A12) to Color(0xFF173B1A)
            Modality.THERMAL -> Color(0xFF1A0B2E) to Color(0xFF3A1546)
            Modality.FUSED -> Color(0xFF243B55) to Color(0xFF3A2A40)
        }
    drawRect(Brush.verticalGradient(listOf(sky, ground)))
    val horizon = size.height * 0.45f
    // Distant ridge line.
    val step = size.width / 48
    for (i in 0 until 48) {
        val x = i * step
        val y = horizon - 10f - 18f * sin(i * 0.7f + 1.3f).toFloat() - 8f * sin(i * 1.9f).toFloat()
        drawRect(ground.copy(alpha = 0.9f), topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(step + 1, size.height - y))
    }
    // Slow drifting scan line so a frozen stream is visually obvious.
    val scanX = ((sceneTimeMs / 40) % size.width.toLong().coerceAtLeast(1)).toFloat()
    drawLine(Color.White.copy(alpha = 0.08f), Offset(scanX, 0f), Offset(scanX, size.height), 3f)
    if (feed == FeedIndicator.DEGRADED || feed == FeedIndicator.OFFLINE || feed == FeedIndicator.STALE) {
        drawRect(Color.Black.copy(alpha = 0.45f))
    }
}
