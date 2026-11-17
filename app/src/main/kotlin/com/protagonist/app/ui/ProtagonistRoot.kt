package com.protagonist.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import capture.designsystem.ControlColors
import capture.designsystem.StatusChip
import capture.presentation.StatusTokens
import com.protagonist.app.CaptureViewModel
import com.protagonist.app.R
import com.protagonist.app.ui.capture.CaptureScreen
import com.protagonist.app.ui.settings.SettingsScreen
import com.protagonist.app.ui.status.SystemStatusScreen

enum class AppTab(
    val labelRes: Int,
    val tag: String,
) {
    CAPTURE(R.string.tab_capture, "tab_capture"),
    SETTINGS(R.string.tab_settings, "tab_settings"),
    SYSTEM_STATUS(R.string.tab_system_status, "tab_system_status"),
}

/**
 * App shell: product header ("Protagonist", from resources) and the three functional tabs.
 * On wide screens header and tabs share one 48dp row to give the observation view maximum height.
 */
@Composable
fun ProtagonistRoot(
    viewModel: CaptureViewModel,
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    // Performance telemetry: UI frame intervals (durations only).
    LaunchedEffect(Unit) {
        var last = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            viewModel.recordUiFrame(now - last)
            last = now
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(ControlColors.Surface)
            .safeDrawingPadding(),
    ) {
        val wide = maxWidth >= 600.dp
        Column(Modifier.fillMaxSize()) {
            val title = @Composable {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ControlColors.OnSurface,
                    modifier =
                        Modifier
                            .padding(horizontal = 12.dp)
                            .semantics { heading() }
                            .testTag("app_header"),
                )
            }
            val linkChip = @Composable {
                StatusChip(
                    style = StatusTokens.link(ui.link.status),
                    text = ui.link.label,
                    spokenPrefix = "Link",
                    modifier = Modifier.padding(horizontal = 8.dp).testTag("link_status"),
                )
            }
            val tabs = @Composable { modifier: Modifier ->
                PrimaryTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    modifier = modifier,
                    containerColor = ControlColors.Surface,
                ) {
                    AppTab.entries.forEach { tab ->
                        Tab(
                            selected = tab == selectedTab,
                            onClick = { onTabSelected(tab) },
                            text = { Text(stringResource(tab.labelRes), maxLines = 1) },
                            modifier = Modifier.testTag(tab.tag),
                        )
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    title()
                    tabs(Modifier.weight(1f))
                    linkChip()
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    title()
                    linkChip()
                }
                tabs(Modifier.fillMaxWidth())
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    AppTab.CAPTURE -> CaptureScreen(ui, viewModel)
                    AppTab.SETTINGS -> SettingsScreen(viewModel)
                    AppTab.SYSTEM_STATUS -> SystemStatusScreen(viewModel)
                }
            }
        }
    }
}
