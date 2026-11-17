package capture.presentation

import capture.domain.CalibrationStatus
import capture.domain.EnqueueEligibility
import capture.domain.JobId
import capture.domain.JobStatus
import capture.domain.LinkDiagnostics
import capture.domain.LinkStatus
import capture.domain.Modality
import capture.domain.NormalizedBox
import capture.domain.SectorId
import capture.domain.SensorHealth
import capture.domain.TrackId
import capture.domain.TrackState
import capture.video.StreamState

/** What the observation view's status chip may claim. "LIVE" is shown only when every condition holds. */
public enum class FeedIndicator {
    /** Real backend, link synced, sensor OK for the mode, frames fresh. */
    LIVE,

    /** Same as LIVE but the source is the simulator. Never presented as live. */
    SIMULATED,

    /** No recent frames. */
    STALE,

    /** Sensor for the current mode is degraded/invalid/offline/unknown. */
    DEGRADED,

    /** Link down: last known state only. */
    OFFLINE,
}

public data class FeedUi(
    val indicator: FeedIndicator,
    val label: String,
    val detail: String?,
)

public data class LinkUi(
    val status: LinkStatus,
    val label: String,
    val simulated: Boolean,
    val detail: String?,
)

public data class OverlayUi(
    val visible: Boolean,
    val calibrated: Boolean,
    /** Shown on the view when overlays are approximate; null when calibrated. */
    val warning: String?,
)

public data class SubjectUi(
    val trackId: TrackId,
    /** Display label, e.g. "S01". Identical in overlay, strip, selected panel and queue. */
    val label: String,
    val state: TrackState,
    val stateLabel: String,
    val box: NormalizedBox?,
    val selected: Boolean,
    val queueStatus: JobStatus?,
    val reason: String?,
    val previewKey: String?,
    val contentDescription: String,
)

public data class SelectedSubjectUi(
    val trackId: TrackId,
    val label: String,
    val state: TrackState,
    val stateLabel: String,
    val reason: String?,
    val observedIn: List<Modality>,
    val lastSeenAgoMs: Long?,
    val queueStatus: JobStatus?,
)

public data class EnqueueUi(
    val enabled: Boolean,
    val blockedReason: String?,
    val blockCode: EnqueueEligibility.BlockReason?,
    val burstLimit: Int,
    val ttlMs: Long,
)

public data class QueueItemUi(
    val key: String,
    val trackId: TrackId,
    val trackLabel: String,
    val jobId: JobId?,
    val status: JobStatus,
    val statusLabel: String,
    /** "frames/limit" only when the backend reported a frame count; never estimated. */
    val progress: String?,
    val reason: String?,
    val cancellable: Boolean,
    val terminal: Boolean,
    val contentDescription: String,
)

public enum class AlertSeverity {
    INFO,
    WARNING,
    CRITICAL,
}

public data class AlertUi(
    val severity: AlertSeverity,
    val text: String,
)

public data class SectorUi(
    val id: SectorId,
    val label: String,
)

public data class CaptureUiState(
    val link: LinkUi,
    val feed: FeedUi,
    val modality: Modality,
    /** Requested by the operator but not yet confirmed by the backend. */
    val pendingModality: Modality?,
    val sectors: List<SectorUi>,
    val activeSector: SectorId?,
    val subjects: List<SubjectUi>,
    val selected: SelectedSubjectUi?,
    val enqueue: EnqueueUi,
    val queue: List<QueueItemUi>,
    val overlay: OverlayUi,
    val alerts: List<AlertUi>,
    val feedback: String?,
    val sceneTimeMs: Long,
) {
    public companion object {
        public val Initial: CaptureUiState =
            CaptureUiState(
                link = LinkUi(LinkStatus.OFFLINE, "Offline", simulated = false, detail = null),
                feed = FeedUi(FeedIndicator.OFFLINE, "OFFLINE", null),
                modality = Modality.VISIBLE,
                pendingModality = null,
                sectors = emptyList(),
                activeSector = null,
                subjects = emptyList(),
                selected = null,
                enqueue = EnqueueUi(false, "Not connected", EnqueueEligibility.BlockReason.LINK_DOWN, 10, 30_000),
                queue = emptyList(),
                overlay = OverlayUi(visible = true, calibrated = false, warning = null),
                alerts = emptyList(),
                feedback = null,
                sceneTimeMs = 0,
            )
    }
}

public data class SensorRowUi(
    val modality: Modality,
    val health: SensorHealth,
    val detail: String?,
)

public data class SystemStatusUiState(
    val link: LinkUi,
    val lastServerSeq: Long,
    val lastSnapshotAgoMs: Long?,
    val reconnectAttempts: Int,
    val diagnostics: LinkDiagnostics,
    val sensors: List<SensorRowUi>,
    val calibration: CalibrationStatus,
    val captureAvailable: Boolean,
    val captureUnavailableReason: String?,
    val videoState: StreamState,
    val videoFramesReceived: Long,
    val videoLastFrameAgoMs: Long?,
    val videoSimulated: Boolean,
    val telemetry: TelemetrySnapshot,
    val sessionId: String?,
)

/** Operator-facing labels. Centralized so wording is consistent and testable. */
public object Labels {
    public fun trackState(state: TrackState): String =
        when (state) {
            TrackState.ACTIVE -> "ACTIVE"
            TrackState.OCCLUDED -> "OCCLUDED"
            TrackState.LOST -> "LOST"
            TrackState.UNCERTAIN -> "UNCERTAIN"
        }

    public fun jobStatus(status: JobStatus): String =
        when (status) {
            JobStatus.PENDING_ACK -> "Pending ack"
            JobStatus.UNCONFIRMED -> "Unconfirmed"
            JobStatus.NOT_RECEIVED -> "Not received"
            JobStatus.QUEUED -> "Queued"
            JobStatus.ACQUIRING -> "Acquiring"
            JobStatus.CAPTURING -> "Capturing"
            JobStatus.COMPLETED -> "Completed"
            JobStatus.FAILED -> "Failed"
            JobStatus.CANCELLED -> "Cancelled"
            JobStatus.REJECTED -> "Rejected"
        }

    public fun modality(modality: Modality): String =
        when (modality) {
            Modality.VISIBLE -> "Visible"
            Modality.LOW_LIGHT -> "Low-light"
            Modality.THERMAL -> "Thermal"
            Modality.FUSED -> "Fused"
        }

    public fun link(status: LinkStatus): String =
        when (status) {
            LinkStatus.OFFLINE -> "Link down"
            LinkStatus.CONNECTING -> "Connecting"
            LinkStatus.SYNCING -> "Syncing"
            LinkStatus.CONNECTED -> "Connected"
        }
}
