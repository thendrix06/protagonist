package capture.presentation

import capture.domain.CaptureRepository
import capture.domain.Clock
import capture.domain.CommandResult
import capture.domain.EnqueueRequest
import capture.domain.IdempotencyKeyFactory
import capture.domain.JobId
import capture.domain.Modality
import capture.domain.SectorId
import capture.domain.TrackId
import capture.hid.ControllerCommand
import capture.hid.ControllerContext
import capture.hid.ControllerInput
import capture.hid.HidCommandMapper
import capture.hid.HidResult
import capture.hid.Screen
import capture.video.VideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Platform-neutral state holder behind the Capture, Settings and System Status screens.
 *
 * The Android `ViewModel` is a thin wrapper around this class, so the acceptance scenarios are verified on the JVM
 * with the deterministic simulator. Selection is local; only [onAddToQueue] (touch) or the HID ENQUEUE input can
 * authorize capture.
 */
public class CaptureStateHolder(
    private val repository: CaptureRepository,
    private val video: VideoSource,
    private val settingsStore: SettingsStore,
    private val keyFactory: IdempotencyKeyFactory,
    private val clock: Clock,
    private val scope: CoroutineScope,
    public val hid: HidCommandMapper = HidCommandMapper(clock),
    public val telemetry: PerformanceTelemetry = PerformanceTelemetry(),
    refreshMs: Long = 250,
) {
    private val requestedModality = MutableStateFlow<Modality?>(null)
    private val feedback = MutableStateFlow<String?>(null)
    private val ticker =
        flow {
            while (true) {
                emit(clock.nowMs())
                delay(refreshMs)
            }
        }

    private data class Core(
        val a: capture.domain.SessionState,
        val b: List<capture.domain.SubjectTrack>,
        val c: List<capture.domain.QueueEntry>,
        val d: TrackId?,
        val e: capture.domain.ConnectionState,
    )

    private data class Env(
        val sensors: capture.domain.SensorState,
        val settings: OperatorSettings,
        val video: capture.video.VideoStreamStatus,
        val requested: Modality?,
        val feedback: String?,
    )

    private val core =
        combine(repository.session, repository.tracks, repository.queue, repository.selectedTrackId, repository.connection, ::Core)
    private val env = combine(repository.sensors, settingsStore.settings, video.status, requestedModality, feedback, ::Env)

    public val uiState: StateFlow<CaptureUiState> =
        combine(core, env, ticker) { c, e, now ->
            val started = System.nanoTime()
            val state =
                CaptureUiReducer.reduce(
                    CaptureInputs(c.a, c.b, c.c, c.d, c.e, e.sensors, e.settings, e.video, e.requested, e.feedback),
                    now,
                )
            telemetry.recordUiStateCompute(System.nanoTime() - started)
            state
        }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, CaptureUiState.Initial)

    public val systemStatus: StateFlow<SystemStatusUiState?> =
        combine(
            combine(repository.connection, repository.diagnostics, repository.sensors, repository.session, ::Quad),
            video.status,
            telemetry.snapshot,
            ticker,
        ) { q, v, t, now ->
            val (conn, diag, sensors, session) = q
            SystemStatusUiState(
                link = LinkUi(conn.status, Labels.link(conn.status), conn.simulated, conn.detail),
                lastServerSeq = conn.lastServerSeq,
                lastSnapshotAgoMs = conn.lastSnapshotAtMs?.let { now - it },
                reconnectAttempts = conn.reconnectAttempts,
                diagnostics = diag,
                sensors =
                    Modality.entries.map { m ->
                        val s = sensors.sensors[m]
                        SensorRowUi(m, s?.health ?: capture.domain.SensorHealth.UNKNOWN, s?.detail)
                    },
                calibration = sensors.calibration,
                captureAvailable = session.captureAvailable,
                captureUnavailableReason = session.captureUnavailableReason,
                videoState = v.state,
                videoFramesReceived = v.framesReceived,
                videoLastFrameAgoMs = v.lastFrameAtMs?.let { now - it },
                videoSimulated = v.simulated,
                telemetry = t,
                sessionId = session.sessionId,
            )
        }.stateIn(scope, SharingStarted.Eagerly, null)

    public val settings: StateFlow<OperatorSettings> = settingsStore.settings

    init {
        // Keep the video stream bound to the backend-confirmed modality and sector.
        scope.launch {
            repository.session
                .map { it.modality to it.activeSector }
                .distinctUntilChanged()
                .collect { (m, s) -> video.bind(m, s) }
        }
        scope.launch {
            settingsStore.settings.map { it.hidEnabled }.distinctUntilChanged().collect { hid.enabled = it }
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Operator actions

    /** Highlight only. Never creates a capture job. */
    public fun onSubjectTapped(trackId: TrackId) {
        repository.selectTrack(trackId)
    }

    public fun onClearSelection() {
        repository.selectTrack(null)
    }

    /** Explicit authorization of the selected subject. The only touch path that creates capture work. */
    public fun onAddToQueue(): Job? {
        // Bind the request to the subject selected at the moment of the tap/press. The operator may keep selecting
        // other subjects before this coroutine runs; that must never redirect the authorization.
        val selected = repository.selectedTrackId.value
        val track = repository.tracks.value.firstOrNull { it.trackId == selected }
        if (track == null) {
            feedback.value = "Select a subject first"
            return null
        }
        val policy = repository.session.value.policy
        val s = settingsStore.settings.value
        val request =
            EnqueueRequest(
                trackId = track.trackId,
                eventId = track.eventId,
                idempotencyKey = keyFactory.next(),
                burstLimit = s.burstLimit.coerceAtMost(policy.maxBurstLimit),
                ttlMs = s.ttlMs.coerceIn(policy.minTtlMs, policy.maxTtlMs),
            )
        // The repository re-checks eligibility atomically (link, track state, duplicates) before transmitting.
        return scope.launch {
            feedback.value =
                when (val r = repository.authorizeEnqueue(request)) {
                    CommandResult.Sent -> null
                    is CommandResult.Refused -> "${request.trackId}: ${r.reason}"
                }
        }
    }

    public fun onCancel(jobId: JobId): Job =
        scope.launch {
            val r = repository.cancelJob(jobId)
            feedback.value = (r as? CommandResult.Refused)?.reason
        }

    public fun onModeSelected(modality: Modality): Job =
        scope.launch {
            requestedModality.value = modality
            val r = repository.setObservationMode(modality)
            if (r is CommandResult.Refused) {
                requestedModality.value = null
                feedback.value = "Mode change not sent: ${r.reason}"
            }
        }

    public fun onSectorSelected(sectorId: SectorId): Job =
        scope.launch {
            val r = repository.selectSector(sectorId)
            feedback.value = (r as? CommandResult.Refused)?.let { "Sector change not sent: ${it.reason}" }
        }

    public fun onDismissFeedback() {
        feedback.value = null
    }

    public fun updateSettings(transform: (OperatorSettings) -> OperatorSettings) {
        settingsStore.update(transform)
    }

    /**
     * Route a controller input. Commands that affect capture state are executed here; tab navigation is returned
     * to the caller ([ControllerCommand.SwitchTab] / [ControllerCommand.GoToCapture]) because tabs are UI-owned.
     */
    public fun onControllerInput(
        input: ControllerInput,
        screen: Screen,
    ): HidResult {
        val state = uiState.value
        val ctx =
            ControllerContext(
                screen = screen,
                hasSubjects = state.subjects.isNotEmpty(),
                hasSelection = state.selected != null,
                enqueueAllowed = state.enqueue.enabled,
                enqueueBlockedReason = state.enqueue.blockedReason,
            )
        val result = hid.onInput(input, ctx)
        if (result is HidResult.Command) {
            when (val c = result.command) {
                ControllerCommand.SelectNextSubject -> {
                    step(state, +1)
                }

                ControllerCommand.SelectPreviousSubject -> {
                    step(state, -1)
                }

                ControllerCommand.ClearSelection -> {
                    onClearSelection()
                }

                ControllerCommand.EnqueueSelected -> {
                    onAddToQueue()
                }

                is ControllerCommand.CycleModality -> {
                    val all = Modality.entries
                    val current = state.pendingModality ?: state.modality
                    onModeSelected(all[(all.indexOf(current) + c.step).mod(all.size)])
                }

                is ControllerCommand.SwitchTab, ControllerCommand.GoToCapture -> {}
            }
        } else if (result is HidResult.Ignored && result.reason == HidResult.Reason.ENQUEUE_BLOCKED) {
            feedback.value = result.detail
        }
        return result
    }

    private fun step(
        state: CaptureUiState,
        delta: Int,
    ) {
        val ids = state.subjects.map { it.trackId }
        if (ids.isEmpty()) return
        val idx = ids.indexOf(state.selected?.trackId)
        val next =
            if (idx < 0) {
                if (delta > 0) 0 else ids.lastIndex
            } else {
                (idx + delta).mod(ids.size)
            }
        repository.selectTrack(ids[next])
    }

    private data class Quad(
        val connection: capture.domain.ConnectionState,
        val diagnostics: capture.domain.LinkDiagnostics,
        val sensors: capture.domain.SensorState,
        val session: capture.domain.SessionState,
    )
}
