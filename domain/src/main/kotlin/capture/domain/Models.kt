package capture.domain

/** Observation modality shown in the single wide observation view. */
public enum class Modality {
    VISIBLE,
    LOW_LIGHT,
    THERMAL,
    FUSED,
}

/** Backend-reported tracking state. The client never infers these on its own. */
public enum class TrackState {
    /** Currently observed and associated with confidence acceptable to the backend. */
    ACTIVE,

    /** Temporarily not visible; the backend still holds the association. */
    OCCLUDED,

    /** No longer observed. The ID is kept for the event but is not re-used for someone else. */
    LOST,

    /** Backend could not confirm the association (e.g. ambiguous reacquisition). Never auto-merged. */
    UNCERTAIN,
}

/**
 * Position of a subject within the panorama in normalized coordinates (0..1 of the full observation frame),
 * expressed in the frame of the calibration the backend used. Not a real-world range.
 */
public data class NormalizedBox(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    init {
        require(width >= 0f && height >= 0f) { "Box size must be non-negative" }
    }

    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f

    public fun contains(
        x: Float,
        y: Float,
    ): Boolean = x in left..right && y in top..bottom
}

/** Reference to an ephemeral, RAM-only preview held by the backend or the local preview cache. */
public data class PreviewRef(
    val key: String,
    val capturedAtMs: Long,
)

public data class SubjectTrack(
    val trackId: TrackId,
    val eventId: EventId,
    val state: TrackState,
    /** Null when the backend has no current position (e.g. LOST). */
    val box: NormalizedBox?,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    /** Modalities the backend currently observes this track in. */
    val observedIn: Set<Modality>,
    /** Backend-provided explanation for OCCLUDED / LOST / UNCERTAIN. Displayed verbatim. */
    val stateReason: String? = null,
    val preview: PreviewRef? = null,
)

/** Capture job lifecycle. [PENDING_ACK], [UNCONFIRMED] and [NOT_RECEIVED] are client-only states. */
public enum class JobStatus(
    public val terminal: Boolean,
    public val clientOnly: Boolean,
) {
    /** Authorization sent; the backend has not acknowledged it yet. NOT the same as queued. */
    PENDING_ACK(terminal = false, clientOnly = true),

    /** Link dropped before the backend acknowledged; outcome unknown until the next snapshot. */
    UNCONFIRMED(terminal = false, clientOnly = true),

    /** A post-reconnect snapshot shows the backend never received the command. Not replayed automatically. */
    NOT_RECEIVED(terminal = true, clientOnly = true),
    QUEUED(terminal = false, clientOnly = false),
    ACQUIRING(terminal = false, clientOnly = false),
    CAPTURING(terminal = false, clientOnly = false),
    COMPLETED(terminal = true, clientOnly = false),
    FAILED(terminal = true, clientOnly = false),
    CANCELLED(terminal = true, clientOnly = false),

    /** Backend refused the authorization (policy, TTL, track state...). */
    REJECTED(terminal = true, clientOnly = false),
}

/**
 * One row of the capture queue. Before the backend acknowledges, [jobId] is null and [status] is
 * [JobStatus.PENDING_ACK]. Frame counts and failure reasons are only ever copied from backend messages.
 */
public data class QueueEntry(
    val idempotencyKey: IdempotencyKey,
    val trackId: TrackId,
    val eventId: EventId,
    val jobId: JobId?,
    val status: JobStatus,
    val burstLimit: Int,
    val ttlMs: Long,
    val requestedAtMs: Long,
    val updatedAtMs: Long,
    val framesCaptured: Int? = null,
    val reason: String? = null,
    val cancellable: Boolean = false,
)

public enum class SensorHealth {
    OK,
    DEGRADED,
    INVALID,
    OFFLINE,
    UNKNOWN,
}

public enum class CalibrationStatus {
    CALIBRATED,
    DEGRADED,
    UNAVAILABLE,
    UNKNOWN,
}

public data class ModalitySensor(
    val modality: Modality,
    val health: SensorHealth,
    val detail: String? = null,
)

public data class SensorState(
    val sensors: Map<Modality, ModalitySensor>,
    /** Cross-modality registration quality needed for the FUSED overlay. */
    val calibration: CalibrationStatus,
    val updatedAtMs: Long,
) {
    public fun healthOf(modality: Modality): SensorHealth = sensors[modality]?.health ?: SensorHealth.UNKNOWN

    public companion object {
        public val Unknown: SensorState = SensorState(emptyMap(), CalibrationStatus.UNKNOWN, 0L)
    }
}

/** Backend-supplied bounds for capture jobs. The client clamps operator settings to these. */
public data class CapturePolicy(
    val maxBurstLimit: Int,
    val minTtlMs: Long,
    val maxTtlMs: Long,
    val operatorCancelAllowed: Boolean,
) {
    public companion object {
        public val Conservative: CapturePolicy =
            CapturePolicy(maxBurstLimit = 20, minTtlMs = 5_000, maxTtlMs = 120_000, operatorCancelAllowed = true)
    }
}

public data class Sector(
    val id: SectorId,
    val label: String,
)

public data class SessionState(
    val sessionId: String?,
    val sectors: List<Sector>,
    val activeSector: SectorId?,
    val modality: Modality,
    /** Backend's statement that new capture jobs can currently be executed. */
    val captureAvailable: Boolean,
    val captureUnavailableReason: String?,
    val policy: CapturePolicy,
) {
    public companion object {
        public val Initial: SessionState =
            SessionState(
                sessionId = null,
                sectors = emptyList(),
                activeSector = null,
                modality = Modality.VISIBLE,
                captureAvailable = false,
                captureUnavailableReason = "Not synchronized with backend",
                policy = CapturePolicy.Conservative,
            )
    }
}

public enum class LinkStatus {
    /** Transport down. */
    OFFLINE,

    /** Transport (re)establishing. */
    CONNECTING,

    /** Transport up, waiting for an authoritative snapshot. Commands that change state are disabled. */
    SYNCING,

    /** Transport up and state reconciled. */
    CONNECTED,
}

public data class ConnectionState(
    val status: LinkStatus,
    val lastServerSeq: Long,
    val lastMessageAtMs: Long?,
    val lastSnapshotAtMs: Long?,
    val reconnectAttempts: Int,
    val detail: String? = null,
    /** True when the backend is the deterministic simulator rather than real hardware. */
    val simulated: Boolean,
) {
    val canCommand: Boolean get() = status == LinkStatus.CONNECTED

    public companion object {
        public fun initial(simulated: Boolean): ConnectionState =
            ConnectionState(LinkStatus.OFFLINE, 0L, null, null, 0, null, simulated)
    }
}

/** Operator request to authorize one bounded capture job. */
public data class EnqueueRequest(
    val trackId: TrackId,
    val eventId: EventId,
    val idempotencyKey: IdempotencyKey,
    val burstLimit: Int,
    val ttlMs: Long,
) {
    init {
        require(burstLimit >= 1) { "burstLimit must be >= 1" }
        require(ttlMs > 0) { "ttlMs must be > 0" }
    }
}

public sealed interface CommandResult {
    /** Accepted for transmission. Does NOT imply the backend queued or captured anything. */
    public data object Sent : CommandResult

    public data class Refused(
        val reason: String,
    ) : CommandResult
}

/** Counters describing the health of the control/state link. Purely diagnostic. */
public data class LinkDiagnostics(
    val messagesReceived: Long = 0,
    val commandsSent: Long = 0,
    val seqGapsDetected: Long = 0,
    val duplicatesDropped: Long = 0,
    val snapshotsApplied: Long = 0,
    val decodeErrors: Long = 0,
    val unsupportedVersionErrors: Long = 0,
    val unknownMessageTypes: Long = 0,
    /** Client receive time minus server timestamp for the last state message. Includes clock skew. */
    val lastApparentLatencyMs: Long? = null,
    val lastError: String? = null,
)
