package com.protagonist.app.di

import android.content.Context
import capture.domain.CaptureRepository
import capture.domain.Clock
import capture.domain.privacy.EphemeralPreviewCache
import capture.domain.privacy.OperationalLog
import capture.presentation.CaptureStateHolder
import capture.presentation.SettingsStore
import capture.presentation.UuidIdempotencyKeyFactory
import capture.remote.CredentialProvider
import capture.remote.RemoteCaptureRepository
import capture.remote.RemoteEndpoint
import capture.sim.FakeCaptureRepository
import capture.sim.SimulatorControls
import capture.video.SimulatedVideoSource
import capture.video.VideoSource
import com.protagonist.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Constructor-injection container (no DI framework). Chooses the backend once:
 *  - empty BuildConfig.CONTROL_URL (default) -> deterministic simulator ("Protagonist Simulator")
 *  - otherwise -> RemoteCaptureRepository over WSS. A production VideoSource (Media3/WebRTC) is a hardware gate
 *    and is not implemented yet, so the remote path still uses the simulated video source and says so.
 */
class AppContainer(
    context: Context,
    val clock: Clock = Clock { System.currentTimeMillis() },
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    controlUrl: String = BuildConfig.CONTROL_URL,
) {
    val settingsStore: SettingsStore = SharedPreferencesSettingsStore(context.applicationContext)

    /** Structured, redacted operational log (identifiers and statuses only), kept in RAM. */
    val operationalLog = OperationalLog(clock)

    /** RAM-only preview store; cleared when the app leaves the foreground. */
    val previewCache = EphemeralPreviewCache<Any>(clock, unselectedTtlMs = settingsStore.settings.value.previewRetentionMs)

    val repository: CaptureRepository
    val simulatorControls: SimulatorControls?

    init {
        val rules = { settingsStore.settings.value.enqueueRules() }
        if (controlUrl.isBlank()) {
            val sim = FakeCaptureRepository.create(scope, clock, rules = rules, log = operationalLog)
            sim.start()
            repository = sim
            simulatorControls = sim
        } else {
            val remote =
                RemoteCaptureRepository.create(
                    endpoint = RemoteEndpoint(controlUrl),
                    credentials = CredentialProvider { null }, // TODO(hardware gate): device credential provisioning
                    scope = scope,
                    clock = clock,
                    rules = rules,
                    log = operationalLog,
                )
            remote.start()
            repository = remote
            simulatorControls = null
        }
    }

    val video: VideoSource =
        SimulatedVideoSource(scope, clock, calibrationProvider = { repository.sensors.value.calibration }).also { it.start() }

    val stateHolder =
        CaptureStateHolder(
            repository = repository,
            video = video,
            settingsStore = settingsStore,
            keyFactory = UuidIdempotencyKeyFactory(),
            clock = clock,
            scope = scope,
        )

    fun onAppBackgrounded() {
        previewCache.clear()
    }

    fun shutdown() {
        video.stop()
        scope.cancel()
    }
}
