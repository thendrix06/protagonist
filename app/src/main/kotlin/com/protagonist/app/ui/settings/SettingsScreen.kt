package com.protagonist.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import capture.designsystem.ControlColors
import capture.designsystem.ControlDimens
import capture.domain.CalibrationStatus
import capture.domain.Modality
import capture.domain.SensorHealth
import capture.domain.TrackState
import capture.presentation.OperatorSettings
import com.protagonist.app.CaptureViewModel

@Composable
fun SettingsScreen(vm: CaptureViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .widthIn(max = 720.dp)
            .testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Capture authorization") {
            Stepper(
                label = "Burst frame limit",
                value = "${s.burstLimit} frames",
                onMinus = { vm.updateSettings { it.copy(burstLimit = (it.burstLimit - 1).coerceIn(OperatorSettings.BURST_RANGE)) } },
                onPlus = { vm.updateSettings { it.copy(burstLimit = (it.burstLimit + 1).coerceIn(OperatorSettings.BURST_RANGE)) } },
                tag = "burst",
            )
            Stepper(
                label = "Authorization TTL",
                value = "${s.ttlMs / 1000} s",
                onMinus = { vm.updateSettings { it.copy(ttlMs = (it.ttlMs - 5_000).coerceIn(OperatorSettings.TTL_RANGE_MS)) } },
                onPlus = { vm.updateSettings { it.copy(ttlMs = (it.ttlMs + 5_000).coerceIn(OperatorSettings.TTL_RANGE_MS)) } },
                tag = "ttl",
            )
            Note("Both are re-clamped to the backend's policy when a job is authorized.")
            Toggle(
                "Allow queueing occluded subjects",
                s.allowOccludedEnqueue,
                { v -> vm.updateSettings { it.copy(allowOccludedEnqueue = v) } },
                "occluded",
            )
            Note("Uncertain and lost subjects can never be queued from this client.")
        }
        Section("Overlay") {
            Toggle(
                "Show overlay when calibration is degraded (marked approximate)",
                s.showUncalibratedOverlay,
                { v -> vm.updateSettings { it.copy(showUncalibratedOverlay = v) } },
                "overlay",
            )
        }
        Section("Privacy & retention") {
            Stepper(
                label = "Unselected preview retention (RAM only)",
                value = "${s.previewRetentionMs / 1000} s",
                onMinus = {
                    vm.updateSettings {
                        it.copy(previewRetentionMs = (it.previewRetentionMs - 5_000).coerceIn(OperatorSettings.PREVIEW_RETENTION_RANGE_MS))
                    }
                },
                onPlus = {
                    vm.updateSettings {
                        it.copy(previewRetentionMs = (it.previewRetentionMs + 5_000).coerceIn(OperatorSettings.PREVIEW_RETENTION_RANGE_MS))
                    }
                },
                tag = "retention",
            )
            Note(
                "Previews are never written to disk and are cleared when the app leaves the foreground. Logs hold only " +
                    "pseudonymous track/job IDs and statuses: no frames, biometric templates or names. This app performs no face recognition.",
            )
        }
        Section("Controller") {
            Toggle("Enable HID / joystick input", s.hidEnabled, { v -> vm.updateSettings { it.copy(hidEnabled = v) } }, "hid")
            Note("D-pad: move highlight · A: highlight · X: ADD TO QUEUE (300 ms debounce) · B: clear/back · L1/R1: mode · L2/R2: tabs")
        }
        vm.simulatorControls?.let { sim ->
            Section("Simulator (development only)") {
                Note("Fault injection for demonstrations and testing. Not present when connected to a real backend.")
                ActionRow("Drop link", "sim_drop_link") { sim.dropLink() }
                ActionRow("Restore link", "sim_restore_link") { sim.restoreLink() }
                ActionRow("Drop link after next command", "sim_drop_after") { sim.dropLinkAfterNextCommand() }
                ActionRow("Inject seq gap", "sim_gap") { sim.injectSeqGap() }
                ActionRow("Thermal sensor INVALID", "sim_thermal_invalid") {
                    sim.setSensorHealth(Modality.THERMAL, SensorHealth.INVALID, "Simulated fault")
                }
                ActionRow("Visible (capture) sensor INVALID", "sim_visible_invalid") {
                    sim.setSensorHealth(Modality.VISIBLE, SensorHealth.INVALID, "Simulated fault")
                }
                ActionRow("All sensors OK", "sim_sensors_ok") { Modality.entries.forEach { sim.setSensorHealth(it, SensorHealth.OK) } }
                ActionRow("Calibration unavailable", "sim_cal_off") { sim.setCalibration(CalibrationStatus.UNAVAILABLE) }
                ActionRow("Calibration OK", "sim_cal_on") { sim.setCalibration(CalibrationStatus.CALIBRATED) }
                ActionRow("Occlude S01 (toggle off with Clear)", "sim_occlude") {
                    sim.forceTrackState("S01", TrackState.OCCLUDED, "Forced by operator")
                }
                ActionRow("Clear forced states", "sim_clear_forced") {
                    listOf("S01", "S02", "S03", "S04").forEach { sim.forceTrackState(it, null) }
                }
                ActionRow("Fail next S02 capture", "sim_fail") { sim.failNextJobFor("S02") }
                ActionRow("Slow acks (3 s)", "sim_slow_ack") { sim.setAckDelayMs(3_000) }
                ActionRow("Normal acks (300 ms)", "sim_fast_ack") { sim.setAckDelayMs(300) }
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    content()
    HorizontalDivider(color = ControlColors.Outline)
}

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = ControlColors.OnSurface.copy(alpha = 0.8f))

@Composable
private fun Toggle(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = ControlDimens.MinTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier =
                Modifier.semantics {
                    contentDescription = label
                }.testTag("toggle_$tag"),
        )
    }
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    tag: String,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = ControlDimens.MinTouchTarget), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = onMinus,
            modifier =
                Modifier.heightIn(min = ControlDimens.MinTouchTarget).semantics {
                    contentDescription = "Decrease $label"
                }.testTag("${tag}_minus"),
        ) { Text("−") }
        Text(value, modifier = Modifier.padding(horizontal = 12.dp).testTag("${tag}_value"), fontWeight = FontWeight.Bold)
        OutlinedButton(
            onClick = onPlus,
            modifier =
                Modifier.heightIn(min = ControlDimens.MinTouchTarget).semantics {
                    contentDescription = "Increase $label"
                }.testTag("${tag}_plus"),
        ) { Text("+") }
    }
}

@Composable
private fun ActionRow(
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = ControlDimens.MinTouchTarget).testTag(tag)) {
        Text(label)
    }
}
