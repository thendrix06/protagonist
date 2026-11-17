package com.protagonist.app.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import capture.designsystem.ControlColors
import capture.designsystem.StatusChip
import capture.domain.SensorHealth
import capture.presentation.FeedIndicator
import capture.presentation.Labels
import capture.presentation.StatusTokens
import com.protagonist.app.BuildConfig
import com.protagonist.app.CaptureViewModel
import com.protagonist.app.R

/** Operational detail kept off the Capture tab: link, sync, sensors, video, telemetry, build identity. */
@Composable
fun SystemStatusScreen(vm: CaptureViewModel) {
    val status by vm.systemStatus.collectAsStateWithLifecycle()
    val s = status
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .widthIn(max = 900.dp)
            .testTag("status_screen"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (s == null) {
            Text("Collecting status…")
            return@Column
        }
        Title("Link")
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(StatusTokens.link(s.link.status), text = s.link.label, spokenPrefix = "Link")
        }
        KV("Backend", if (s.link.simulated) "Protagonist Simulator (deterministic, no hardware)" else "Remote (Protagonist Core)")
        KV("Session", s.sessionId ?: "—")
        KV("Last state seq", s.lastServerSeq.toString())
        KV("Last snapshot", s.lastSnapshotAgoMs?.let { "${it / 1000}s ago" } ?: "never")
        KV("Reconnect attempts", s.reconnectAttempts.toString())
        s.link.detail?.let { KV("Detail", it) }
        KV("Messages received", s.diagnostics.messagesReceived.toString())
        KV("Commands sent", s.diagnostics.commandsSent.toString())
        KV("Seq gaps / duplicates dropped", "${s.diagnostics.seqGapsDetected} / ${s.diagnostics.duplicatesDropped}")
        KV("Snapshots applied", s.diagnostics.snapshotsApplied.toString())
        KV("Decode / version errors", "${s.diagnostics.decodeErrors} / ${s.diagnostics.unsupportedVersionErrors}")
        KV("Apparent event latency (incl. clock skew)", s.diagnostics.lastApparentLatencyMs?.let { "$it ms" } ?: "—")
        s.diagnostics.lastError?.let { KV("Last error", it) }
        HorizontalDivider(color = ControlColors.Outline)

        Title("Sensors (backend-reported)")
        s.sensors.forEach { row ->
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("sensor_${row.modality.name}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(Labels.modality(row.modality), modifier = Modifier.weight(1f))
                val indicator = if (row.health == SensorHealth.OK) FeedIndicator.SIMULATED else FeedIndicator.DEGRADED
                StatusChip(
                    StatusTokens.feed(indicator),
                    text = row.health.name,
                    spokenPrefix = "${Labels.modality(row.modality)} sensor",
                )
                row.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        }
        KV("Cross-modality calibration", s.calibration.name)
        KV("Capture available", if (s.captureAvailable) "yes" else "no — ${s.captureUnavailableReason ?: "unknown"}")
        HorizontalDivider(color = ControlColors.Outline)

        Title("Video")
        KV("Stream", "${s.videoState.name}${if (s.videoSimulated) " (simulated)" else ""}")
        KV("Frames received", s.videoFramesReceived.toString())
        KV("Last frame", s.videoLastFrameAgoMs?.let { "$it ms ago" } ?: "—")
        HorizontalDivider(color = ControlColors.Outline)

        Title("Performance")
        KV(
            "UI state computations",
            "${s.telemetry.uiStateComputations} (avg ${s.telemetry.uiStateComputeAvgMicros} µs, max ${s.telemetry.uiStateComputeMaxMicros} µs)",
        )
        KV("UI frames / janky (>32 ms) / worst", "${s.telemetry.uiFrames} / ${s.telemetry.uiJankFrames} / ${s.telemetry.uiFrameWorstMs} ms")
        HorizontalDivider(color = ControlColors.Outline)

        Title("Build")
        KV("Product", stringResource(R.string.subsystem_control))
        KV("Version", BuildConfig.VERSION_NAME)
        KV("Application ID (provisional)", BuildConfig.APPLICATION_ID)
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

@Composable
private fun KV(
    k: String,
    v: String,
) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(k, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(v, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
