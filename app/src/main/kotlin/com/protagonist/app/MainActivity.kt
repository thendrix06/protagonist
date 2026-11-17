package com.protagonist.app

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import capture.designsystem.ControlTheme
import capture.hid.ControllerCommand
import capture.hid.HidResult
import capture.hid.Screen
import com.protagonist.app.hid.AndroidHidAdapter
import com.protagonist.app.ui.AppTab
import com.protagonist.app.ui.ProtagonistRoot

class MainActivity : ComponentActivity() {
    private val container by lazy { (application as ProtagonistApplication).container }
    private val viewModel: CaptureViewModel by viewModels {
        CaptureViewModel.Factory(container.stateHolder, container.simulatorControls)
    }
    private val hid = AndroidHidAdapter()
    private val tab = mutableStateOf(AppTab.CAPTURE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ControlTheme {
                ProtagonistRoot(viewModel = viewModel, selectedTab = tab.value, onTabSelected = { tab.value = it })
            }
        }
    }

    // Performance telemetry from the platform's passive frame metrics: reports only frames that were actually
    // drawn, and never requests extra frames (an always-on frame loop would keep the UI rendering and drain battery).
    private val metricsThread by lazy { HandlerThread("frame-metrics").apply { start() } }
    private val frameMetricsListener =
        Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            viewModel.recordUiFrame(metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000)
        }

    override fun onResume() {
        super.onResume()
        window.addOnFrameMetricsAvailableListener(frameMetricsListener, Handler(metricsThread.looper))
    }

    override fun onPause() {
        window.removeOnFrameMetricsAvailableListener(frameMetricsListener)
        super.onPause()
    }

    override fun onDestroy() {
        metricsThread.quitSafely()
        super.onDestroy()
    }

    /** Optional HID / joystick input. Touch remains fully functional without it. */
    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val input = hid.map(event) ?: return super.onKeyDown(keyCode, event)
        val screen =
            when (tab.value) {
                AppTab.CAPTURE -> Screen.CAPTURE
                AppTab.SETTINGS -> Screen.SETTINGS
                AppTab.SYSTEM_STATUS -> Screen.SYSTEM_STATUS
            }
        val result = viewModel.onControllerInput(input, screen)
        if (result is HidResult.Command) {
            when (val c = result.command) {
                is ControllerCommand.SwitchTab -> tab.value = AppTab.entries[(tab.value.ordinal + c.step).mod(AppTab.entries.size)]
                ControllerCommand.GoToCapture -> tab.value = AppTab.CAPTURE
                else -> Unit
            }
            return true
        }
        // Ignored results (debounced / blocked) are consumed so they don't trigger default focus navigation twice.
        val consumed = result is HidResult.Ignored && result.reason != HidResult.Reason.DISABLED
        return consumed || super.onKeyDown(keyCode, event)
    }
}
