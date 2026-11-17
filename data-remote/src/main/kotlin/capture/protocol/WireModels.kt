package capture.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire DTOs for contracts/capture_protocol.json. Non-null properties without defaults are REQUIRED on the wire;
// nullable properties with a null default are OPTIONAL. Unknown keys are ignored by the codec.

@Serializable
public enum class WireModality {
    @SerialName("visible")
    VISIBLE,

    @SerialName("low_light")
    LOW_LIGHT,

    @SerialName("thermal")
    THERMAL,

    @SerialName("fused")
    FUSED,
}

@Serializable
public enum class WireTrackState {
    @SerialName("active")
    ACTIVE,

    @SerialName("occluded")
    OCCLUDED,

    @SerialName("lost")
    LOST,

    @SerialName("uncertain")
    UNCERTAIN,
}

@Serializable
public enum class WireJobStatus {
    @SerialName("queued")
    QUEUED,

    @SerialName("acquiring")
    ACQUIRING,

    @SerialName("capturing")
    CAPTURING,

    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("rejected")
    REJECTED,
}

@Serializable
public enum class WireSensorHealth {
    @SerialName("ok")
    OK,

    @SerialName("degraded")
    DEGRADED,

    @SerialName("invalid")
    INVALID,

    @SerialName("offline")
    OFFLINE,
}

@Serializable
public enum class WireCalibration {
    @SerialName("calibrated")
    CALIBRATED,

    @SerialName("degraded")
    DEGRADED,

    @SerialName("unavailable")
    UNAVAILABLE,
}

@Serializable
public enum class WireCommand {
    @SerialName("enqueue")
    ENQUEUE,

    @SerialName("cancel")
    CANCEL,

    @SerialName("set_mode")
    SET_MODE,

    @SerialName("set_sector")
    SET_SECTOR,

    @SerialName("snapshot_request")
    SNAPSHOT_REQUEST,
}

@Serializable
public data class WireBox(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

@Serializable
public data class WirePreview(
    val key: String,
    val capturedAtMs: Long,
)

@Serializable
public data class WireTrack(
    val trackId: String,
    val eventId: String,
    val state: WireTrackState,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val observedIn: List<WireModality>,
    val box: WireBox? = null,
    val stateReason: String? = null,
    val preview: WirePreview? = null,
)

@Serializable
public data class WireJob(
    val jobId: String,
    val idempotencyKey: String,
    val trackId: String,
    val eventId: String,
    val status: WireJobStatus,
    val burstLimit: Int,
    val ttlMs: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val cancellable: Boolean,
    val framesCaptured: Int? = null,
    val reason: String? = null,
)

@Serializable
public data class WireSensor(
    val modality: WireModality,
    val health: WireSensorHealth,
    val detail: String? = null,
)

@Serializable
public data class WireSector(
    val sectorId: String,
    val label: String,
)

@Serializable
public data class WirePolicy(
    val maxBurstLimit: Int,
    val minTtlMs: Long,
    val maxTtlMs: Long,
    val operatorCancelAllowed: Boolean,
)

/** Typed message payloads. The envelope `type` string selects the payload class. */
public sealed interface Payload {
    @Serializable
    public data class Snapshot(
        val sessionId: String,
        val lastClientSeqProcessed: Long,
        val sectors: List<WireSector>,
        val modality: WireModality,
        val captureAvailable: Boolean,
        val policy: WirePolicy,
        val tracks: List<WireTrack>,
        val jobs: List<WireJob>,
        val sensors: SensorUpdate,
        val activeSectorId: String? = null,
        val captureUnavailableReason: String? = null,
    ) : Payload

    @Serializable
    public data class TrackUpdate(
        val track: WireTrack,
    ) : Payload

    @Serializable
    public data class TrackRemoved(
        val trackId: String,
        val eventId: String,
    ) : Payload

    @Serializable
    public data class JobUpdate(
        val job: WireJob,
    ) : Payload

    @Serializable
    public data class SensorUpdate(
        val sensors: List<WireSensor>,
        val calibration: WireCalibration,
    ) : Payload

    @Serializable
    public data class SessionUpdate(
        val modality: WireModality,
        val captureAvailable: Boolean,
        val activeSectorId: String? = null,
        val captureUnavailableReason: String? = null,
    ) : Payload

    @Serializable
    public data class CommandAck(
        val clientSeq: Long,
        val command: WireCommand,
        val accepted: Boolean,
        val idempotencyKey: String? = null,
        val jobId: String? = null,
        val reason: String? = null,
    ) : Payload

    @Serializable
    public data class Heartbeat(
        val note: String? = null,
    ) : Payload

    @Serializable
    public data class Enqueue(
        val idempotencyKey: String,
        val trackId: String,
        val eventId: String,
        val burstLimit: Int,
        val ttlMs: Long,
    ) : Payload

    @Serializable
    public data class Cancel(
        val jobId: String,
        val idempotencyKey: String,
    ) : Payload

    @Serializable
    public data class SetMode(
        val modality: WireModality,
    ) : Payload

    @Serializable
    public data class SetSector(
        val sectorId: String,
    ) : Payload

    @Serializable
    public data class SnapshotRequest(
        val reason: String? = null,
    ) : Payload

    /** A message type this client does not know. Ignored, but its seq is still consumed. */
    public data class Unknown(
        val type: String,
    ) : Payload
}

public enum class Stream(
    public val wire: String,
) {
    STATE("state"),
    CONTROL("control"),
    HEARTBEAT("heartbeat"),
    ;

    public companion object {
        public fun fromWire(value: String): Stream? = entries.firstOrNull { it.wire == value }
    }
}

public data class Envelope(
    val protocolVersion: ProtocolVersion,
    val type: String,
    val stream: Stream,
    val seq: Long,
    val timestampMs: Long,
    val payload: Payload,
)
