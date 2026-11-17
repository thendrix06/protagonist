package com.protagonist.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import capture.domain.JobId
import capture.domain.Modality
import capture.domain.SectorId
import capture.domain.TrackId
import capture.hid.ControllerInput
import capture.hid.HidResult
import capture.hid.Screen
import capture.presentation.CaptureStateHolder
import capture.presentation.CaptureUiState
import capture.presentation.OperatorSettings
import capture.presentation.SystemStatusUiState
import capture.sim.SimulatorControls
import kotlinx.coroutines.flow.StateFlow

/** Thin Android wrapper; all behaviour lives in the platform-neutral [CaptureStateHolder] (unit-tested on the JVM). */
class CaptureViewModel(
    private val holder: CaptureStateHolder,
    val simulatorControls: SimulatorControls?,
) : ViewModel() {
    val uiState: StateFlow<CaptureUiState> = holder.uiState
    val systemStatus: StateFlow<SystemStatusUiState?> = holder.systemStatus
    val settings: StateFlow<OperatorSettings> = holder.settings

    fun onSubjectTapped(id: TrackId) = holder.onSubjectTapped(id)

    fun onClearSelection() = holder.onClearSelection()

    fun onAddToQueue() {
        holder.onAddToQueue()
    }

    fun onCancel(jobId: JobId) {
        holder.onCancel(jobId)
    }

    fun onModeSelected(m: Modality) {
        holder.onModeSelected(m)
    }

    fun onSectorSelected(id: SectorId) {
        holder.onSectorSelected(id)
    }

    fun onDismissFeedback() = holder.onDismissFeedback()

    fun updateSettings(transform: (OperatorSettings) -> OperatorSettings) = holder.updateSettings(transform)

    fun onControllerInput(
        input: ControllerInput,
        screen: Screen,
    ): HidResult = holder.onControllerInput(input, screen)

    fun recordUiFrame(intervalMs: Long) = holder.telemetry.recordUiFrame(intervalMs)

    class Factory(
        private val holder: CaptureStateHolder,
        private val sim: SimulatorControls?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CaptureViewModel(holder, sim) as T
    }
}
