package com.protagonist.app.di

import android.content.Context
import androidx.core.content.edit
import capture.presentation.OperatorSettings
import capture.presentation.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persists operator preferences only (numbers and toggles). No imagery, identifiers or names are ever stored. */
class SharedPreferencesSettingsStore(
    context: Context,
) : SettingsStore {
    private val prefs = context.getSharedPreferences("operator_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    override val settings: StateFlow<OperatorSettings> = state.asStateFlow()

    override fun update(transform: (OperatorSettings) -> OperatorSettings) {
        val next = transform(state.value)
        prefs.edit {
            putInt(KEY_BURST, next.burstLimit)
            putLong(KEY_TTL, next.ttlMs)
            putBoolean(KEY_OCCLUDED, next.allowOccludedEnqueue)
            putLong(KEY_PREVIEW, next.previewRetentionMs)
            putBoolean(KEY_OVERLAY, next.showUncalibratedOverlay)
            putBoolean(KEY_HID, next.hidEnabled)
        }
        state.value = next
    }

    private fun load(): OperatorSettings {
        val d = OperatorSettings()
        return runCatching {
            OperatorSettings(
                burstLimit = prefs.getInt(KEY_BURST, d.burstLimit),
                ttlMs = prefs.getLong(KEY_TTL, d.ttlMs),
                allowOccludedEnqueue = prefs.getBoolean(KEY_OCCLUDED, d.allowOccludedEnqueue),
                previewRetentionMs = prefs.getLong(KEY_PREVIEW, d.previewRetentionMs),
                showUncalibratedOverlay = prefs.getBoolean(KEY_OVERLAY, d.showUncalibratedOverlay),
                hidEnabled = prefs.getBoolean(KEY_HID, d.hidEnabled),
            )
        }.getOrDefault(d)
    }

    private companion object {
        const val KEY_BURST = "burst_limit"
        const val KEY_TTL = "ttl_ms"
        const val KEY_OCCLUDED = "allow_occluded"
        const val KEY_PREVIEW = "preview_retention_ms"
        const val KEY_OVERLAY = "show_uncalibrated_overlay"
        const val KEY_HID = "hid_enabled"
    }
}
