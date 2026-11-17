package capture.presentation

import capture.domain.CalibrationStatus
import capture.domain.ConnectionState
import capture.domain.EnqueueEligibility
import capture.domain.EnqueuePolicy
import capture.domain.LinkStatus
import capture.domain.Modality
import capture.domain.QueueEntry
import capture.domain.SensorHealth
import capture.domain.SensorState
import capture.domain.SessionState
import capture.domain.SubjectTrack
import capture.domain.TrackId
import capture.video.StreamState
import capture.video.VideoStreamStatus

/** Everything the Capture screen derives from. */
public data class CaptureInputs(
    val session: SessionState,
    val tracks: List<SubjectTrack>,
    val queue: List<QueueEntry>,
    val selected: TrackId?,
    val connection: ConnectionState,
    val sensors: SensorState,
    val settings: OperatorSettings,
    val video: VideoStreamStatus,
    val requestedModality: Modality?,
    val feedback: String?,
)

/**
 * Pure function from repository state to [CaptureUiState]. It only re-labels backend facts; it never invents
 * ranges, confidences, capture results or sensor health.
 */
public object CaptureUiReducer {
    public const val STALE_FRAME_MS: Long = 1_500

    public fun reduce(
        input: CaptureInputs,
        nowMs: Long,
    ): CaptureUiState {
        val session = input.session
        val modality = session.modality
        val latestEntryByTrack: Map<TrackId, QueueEntry> = input.queue.groupBy { it.trackId }.mapValues { (_, v) -> v.last() }

        val orderedTracks = input.tracks.sortedWith(compareBy({ it.firstSeenMs }, { it.trackId.value }))
        val subjects =
            orderedTracks.map { t ->
                val stateLabel = Labels.trackState(t.state)
                val q = latestEntryByTrack[t.trackId]?.status
                val selected = t.trackId == input.selected
                SubjectUi(
                    trackId = t.trackId,
                    label = t.trackId.value,
                    state = t.state,
                    stateLabel = stateLabel,
                    box = t.box,
                    selected = selected,
                    queueStatus = q,
                    reason = t.stateReason,
                    previewKey = t.preview?.key,
                    contentDescription =
                        buildString {
                            append("Subject ${t.trackId.value}, $stateLabel")
                            if (selected) append(", selected")
                            if (q != null) append(", job ${Labels.jobStatus(q)}")
                        },
                )
            }

        val selectedTrack = input.tracks.firstOrNull { it.trackId == input.selected }
        val selectedUi =
            selectedTrack?.let { t ->
                SelectedSubjectUi(
                    trackId = t.trackId,
                    label = t.trackId.value,
                    state = t.state,
                    stateLabel = Labels.trackState(t.state),
                    reason = t.stateReason,
                    observedIn = t.observedIn.sortedBy { it.ordinal },
                    lastSeenAgoMs = (nowMs - t.lastSeenMs).coerceAtLeast(0),
                    queueStatus = latestEntryByTrack[t.trackId]?.status,
                )
            }

        val eligibility =
            EnqueuePolicy.evaluate(selectedTrack, input.connection, session, input.queue, input.settings.enqueueRules())
        val policy = session.policy
        val enqueue =
            EnqueueUi(
                enabled = eligibility is EnqueueEligibility.Allowed,
                blockedReason = (eligibility as? EnqueueEligibility.Blocked)?.detail,
                blockCode = (eligibility as? EnqueueEligibility.Blocked)?.reason,
                burstLimit = input.settings.burstLimit.coerceAtMost(policy.maxBurstLimit),
                ttlMs = input.settings.ttlMs.coerceIn(policy.minTtlMs, policy.maxTtlMs),
            )

        val queue =
            input.queue.map { e ->
                val label = Labels.jobStatus(e.status)
                val progress = e.framesCaptured?.let { "$it/${e.burstLimit}" }
                QueueItemUi(
                    key = e.idempotencyKey.value,
                    trackId = e.trackId,
                    trackLabel = e.trackId.value,
                    jobId = e.jobId,
                    status = e.status,
                    statusLabel = label,
                    progress = progress,
                    reason = e.reason,
                    cancellable = e.cancellable && policy.operatorCancelAllowed && input.connection.canCommand && !e.status.terminal,
                    terminal = e.status.terminal,
                    contentDescription =
                        buildString {
                            append("Capture job for ${e.trackId.value}: $label")
                            if (progress != null) append(", $progress frames reported")
                            if (e.reason != null) append(", ${e.reason}")
                        },
                )
            }

        val feed = feed(input, modality, nowMs)
        val overlay = overlay(input, modality)
        val link =
            LinkUi(
                status = input.connection.status,
                label = Labels.link(input.connection.status) + if (input.connection.simulated) " (simulator)" else "",
                simulated = input.connection.simulated,
                detail = input.connection.detail,
            )

        return CaptureUiState(
            link = link,
            feed = feed,
            modality = modality,
            pendingModality = input.requestedModality?.takeIf { it != modality },
            sectors = session.sectors.map { SectorUi(it.id, it.label) },
            activeSector = session.activeSector,
            subjects = subjects,
            selected = selectedUi,
            enqueue = enqueue,
            queue = queue,
            overlay = overlay,
            alerts = alerts(input),
            feedback = input.feedback,
            sceneTimeMs = input.video.lastFrameAtMs ?: nowMs,
        )
    }

    /** Sensors whose health determines whether the current view is trustworthy. */
    public fun sensorsFor(modality: Modality): List<Modality> =
        when (modality) {
            Modality.FUSED -> listOf(Modality.VISIBLE, Modality.THERMAL)
            else -> listOf(modality)
        }

    private fun feed(
        input: CaptureInputs,
        modality: Modality,
        nowMs: Long,
    ): FeedUi {
        if (input.connection.status != LinkStatus.CONNECTED) {
            return FeedUi(FeedIndicator.OFFLINE, "LINK DOWN", "Showing last known state")
        }
        val unhealthy =
            sensorsFor(modality)
                .map { it to input.sensors.healthOf(it) }
                .filter { it.second != SensorHealth.OK }
        if (unhealthy.isNotEmpty()) {
            val detail = unhealthy.joinToString { (m, h) -> "${Labels.modality(m)} sensor ${h.name}" }
            return FeedUi(FeedIndicator.DEGRADED, "DEGRADED", detail)
        }
        val lastFrame = input.video.lastFrameAtMs
        val fresh = input.video.state == StreamState.STREAMING && lastFrame != null && nowMs - lastFrame <= STALE_FRAME_MS
        if (!fresh) {
            return FeedUi(FeedIndicator.STALE, "NO VIDEO", "No recent frames")
        }
        return if (input.video.simulated || input.connection.simulated) {
            FeedUi(FeedIndicator.SIMULATED, "SIMULATED", "Simulator feed, not a live sensor")
        } else {
            FeedUi(FeedIndicator.LIVE, "LIVE", null)
        }
    }

    private fun overlay(
        input: CaptureInputs,
        modality: Modality,
    ): OverlayUi {
        if (modality != Modality.FUSED) return OverlayUi(visible = true, calibrated = true, warning = null)
        return when (input.sensors.calibration) {
            CalibrationStatus.CALIBRATED -> {
                OverlayUi(visible = true, calibrated = true, warning = null)
            }

            CalibrationStatus.DEGRADED, CalibrationStatus.UNAVAILABLE, CalibrationStatus.UNKNOWN -> {
                OverlayUi(
                    visible = input.settings.showUncalibratedOverlay,
                    calibrated = false,
                    warning = "OVERLAY UNCALIBRATED (${input.sensors.calibration.name}): positions approximate",
                )
            }
        }
    }

    private fun alerts(input: CaptureInputs): List<AlertUi> =
        buildList {
            when (input.connection.status) {
                LinkStatus.OFFLINE, LinkStatus.CONNECTING -> {
                    add(
                        AlertUi(
                            AlertSeverity.CRITICAL,
                            "Link down: new captures disabled. Authorized jobs continue under backend policy.",
                        ),
                    )
                }

                LinkStatus.SYNCING -> {
                    add(AlertUi(AlertSeverity.WARNING, input.connection.detail ?: "Reconciling with backend"))
                }

                LinkStatus.CONNECTED -> {}
            }
            if (input.connection.status == LinkStatus.CONNECTED && !input.session.captureAvailable) {
                add(AlertUi(AlertSeverity.CRITICAL, "Capture unavailable: ${input.session.captureUnavailableReason ?: "backend"}"))
            }
            input.sensors.sensors.values
                .filter { it.health == SensorHealth.INVALID || it.health == SensorHealth.OFFLINE }
                .sortedBy { it.modality.ordinal }
                .forEach {
                    add(
                        AlertUi(
                            AlertSeverity.WARNING,
                            "${Labels.modality(it.modality)} sensor ${it.health.name}" + (it.detail?.let { d -> ": $d" } ?: ""),
                        ),
                    )
                }
        }
}
