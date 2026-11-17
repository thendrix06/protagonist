package capture.protocol

import capture.domain.CalibrationStatus
import capture.domain.CapturePolicy
import capture.domain.EventId
import capture.domain.IdempotencyKey
import capture.domain.JobId
import capture.domain.JobStatus
import capture.domain.Modality
import capture.domain.ModalitySensor
import capture.domain.NormalizedBox
import capture.domain.PreviewRef
import capture.domain.Sector
import capture.domain.SectorId
import capture.domain.SensorHealth
import capture.domain.SensorState
import capture.domain.SubjectTrack
import capture.domain.TrackId
import capture.domain.TrackState

// Wire <-> domain mapping. Kept separate from both so neither depends on the other's representation.

public fun WireModality.toDomain(): Modality =
    when (this) {
        WireModality.VISIBLE -> Modality.VISIBLE
        WireModality.LOW_LIGHT -> Modality.LOW_LIGHT
        WireModality.THERMAL -> Modality.THERMAL
        WireModality.FUSED -> Modality.FUSED
    }

public fun Modality.toWire(): WireModality =
    when (this) {
        Modality.VISIBLE -> WireModality.VISIBLE
        Modality.LOW_LIGHT -> WireModality.LOW_LIGHT
        Modality.THERMAL -> WireModality.THERMAL
        Modality.FUSED -> WireModality.FUSED
    }

public fun WireTrackState.toDomain(): TrackState =
    when (this) {
        WireTrackState.ACTIVE -> TrackState.ACTIVE
        WireTrackState.OCCLUDED -> TrackState.OCCLUDED
        WireTrackState.LOST -> TrackState.LOST
        WireTrackState.UNCERTAIN -> TrackState.UNCERTAIN
    }

public fun TrackState.toWire(): WireTrackState =
    when (this) {
        TrackState.ACTIVE -> WireTrackState.ACTIVE
        TrackState.OCCLUDED -> WireTrackState.OCCLUDED
        TrackState.LOST -> WireTrackState.LOST
        TrackState.UNCERTAIN -> WireTrackState.UNCERTAIN
    }

public fun WireJobStatus.toDomain(): JobStatus =
    when (this) {
        WireJobStatus.QUEUED -> JobStatus.QUEUED
        WireJobStatus.ACQUIRING -> JobStatus.ACQUIRING
        WireJobStatus.CAPTURING -> JobStatus.CAPTURING
        WireJobStatus.COMPLETED -> JobStatus.COMPLETED
        WireJobStatus.FAILED -> JobStatus.FAILED
        WireJobStatus.CANCELLED -> JobStatus.CANCELLED
        WireJobStatus.REJECTED -> JobStatus.REJECTED
    }

public fun WireSensorHealth.toDomain(): SensorHealth =
    when (this) {
        WireSensorHealth.OK -> SensorHealth.OK
        WireSensorHealth.DEGRADED -> SensorHealth.DEGRADED
        WireSensorHealth.INVALID -> SensorHealth.INVALID
        WireSensorHealth.OFFLINE -> SensorHealth.OFFLINE
    }

public fun SensorHealth.toWire(): WireSensorHealth =
    when (this) {
        SensorHealth.OK -> WireSensorHealth.OK
        SensorHealth.DEGRADED -> WireSensorHealth.DEGRADED
        SensorHealth.INVALID -> WireSensorHealth.INVALID
        SensorHealth.OFFLINE, SensorHealth.UNKNOWN -> WireSensorHealth.OFFLINE
    }

public fun WireCalibration.toDomain(): CalibrationStatus =
    when (this) {
        WireCalibration.CALIBRATED -> CalibrationStatus.CALIBRATED
        WireCalibration.DEGRADED -> CalibrationStatus.DEGRADED
        WireCalibration.UNAVAILABLE -> CalibrationStatus.UNAVAILABLE
    }

public fun CalibrationStatus.toWire(): WireCalibration =
    when (this) {
        CalibrationStatus.CALIBRATED -> WireCalibration.CALIBRATED
        CalibrationStatus.DEGRADED -> WireCalibration.DEGRADED
        CalibrationStatus.UNAVAILABLE, CalibrationStatus.UNKNOWN -> WireCalibration.UNAVAILABLE
    }

public fun WireTrack.toDomain(): SubjectTrack =
    SubjectTrack(
        trackId = TrackId(trackId),
        eventId = EventId(eventId),
        state = state.toDomain(),
        box = box?.let { NormalizedBox(it.left, it.top, it.width, it.height) },
        firstSeenMs = firstSeenMs,
        lastSeenMs = lastSeenMs,
        observedIn = observedIn.map { it.toDomain() }.toSet(),
        stateReason = stateReason,
        preview = preview?.let { PreviewRef(it.key, it.capturedAtMs) },
    )

public fun SubjectTrack.toWire(): WireTrack =
    WireTrack(
        trackId = trackId.value,
        eventId = eventId.value,
        state = state.toWire(),
        firstSeenMs = firstSeenMs,
        lastSeenMs = lastSeenMs,
        observedIn = observedIn.sortedBy { it.ordinal }.map { it.toWire() },
        box = box?.let { WireBox(it.left, it.top, it.width, it.height) },
        stateReason = stateReason,
        preview = preview?.let { WirePreview(it.key, it.capturedAtMs) },
    )

public fun Payload.SensorUpdate.toDomain(atMs: Long): SensorState =
    SensorState(
        sensors =
            sensors.associate { s ->
                val m = s.modality.toDomain()
                m to ModalitySensor(m, s.health.toDomain(), s.detail)
            },
        calibration = calibration.toDomain(),
        updatedAtMs = atMs,
    )

public fun WirePolicy.toDomain(): CapturePolicy = CapturePolicy(maxBurstLimit, minTtlMs, maxTtlMs, operatorCancelAllowed)

public fun CapturePolicy.toWire(): WirePolicy = WirePolicy(maxBurstLimit, minTtlMs, maxTtlMs, operatorCancelAllowed)

public fun WireSector.toDomain(): Sector = Sector(SectorId(sectorId), label)

public fun WireJob.idempotencyKeyOrNull(): IdempotencyKey? = runCatching { IdempotencyKey(idempotencyKey) }.getOrNull()

public fun WireJob.jobIdDomain(): JobId = JobId(jobId)
