package capture.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Single port between the operator UI and a capture backend.
 *
 * Implementations: the deterministic simulator (`FakeCaptureRepository`) and the networked
 * `RemoteCaptureRepository`. Compose screens depend only on this interface.
 *
 * Invariants every implementation must uphold:
 *  - [selectTrack] is purely local. It never transmits anything and never authorizes a capture.
 *  - [authorizeEnqueue] is the only path that creates capture work, and only on an explicit operator action.
 *  - Queue entries stay [JobStatus.PENDING_ACK] until the backend acknowledges. Status, frame counts, ranges,
 *    sensor health and failure reasons are only ever reported from backend messages, never invented.
 *  - After a link loss, enqueue commands are never replayed automatically; state is reconciled from a snapshot
 *    using idempotency keys.
 */
public interface CaptureRepository {
    public val session: StateFlow<SessionState>
    public val tracks: StateFlow<List<SubjectTrack>>
    public val queue: StateFlow<List<QueueEntry>>
    public val sensors: StateFlow<SensorState>
    public val connection: StateFlow<ConnectionState>

    /** Transport/sync counters for the System Status screen. */
    public val diagnostics: StateFlow<LinkDiagnostics>

    /** Locally highlighted track. Never sent to the backend. */
    public val selectedTrackId: StateFlow<TrackId?>

    /** Local highlight only. Passing null clears the selection. */
    public fun selectTrack(trackId: TrackId?)

    /** Authorize one bounded capture job. Returns [CommandResult.Refused] without transmitting if not allowed. */
    public suspend fun authorizeEnqueue(request: EnqueueRequest): CommandResult

    /** Ask the backend to cancel an authorized job; the backend decides whether that is allowed. */
    public suspend fun cancelJob(jobId: JobId): CommandResult

    public suspend fun setObservationMode(modality: Modality): CommandResult

    public suspend fun selectSector(sectorId: SectorId): CommandResult
}
