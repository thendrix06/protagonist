package capture.video

import capture.domain.CalibrationStatus
import capture.domain.Clock
import capture.domain.Modality
import capture.domain.SectorId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Per-frame registration metadata. Detection boxes are expressed in the panorama frame; [status] says whether the
 * current modality's pixels are registered to it well enough to draw overlays as exact.
 */
public data class FrameCalibration(
    val status: CalibrationStatus,
    /** Opaque identifier of the calibration set the backend used (for audit / mismatch detection). */
    val calibrationId: String?,
)

/** What to draw. Production adapters hand the UI a platform surface; the simulator hands a scene time. */
public sealed interface FrameContent {
    /** Synthetic frame: the UI renders a procedural scene for [sceneTimeMs]. Always labelled as simulated. */
    public data class Synthetic(
        val sceneTimeMs: Long,
    ) : FrameContent

    /** Platform-specific handle (e.g. a Media3 player or WebRTC track), opaque to platform-neutral code. */
    public class External(
        public val handle: Any,
    ) : FrameContent
}

public data class VideoFrame(
    val seq: Long,
    /** UTC epoch ms at capture (from the source, never the receiver). */
    val timestampMs: Long,
    val modality: Modality,
    val widthPx: Int,
    val heightPx: Int,
    val calibration: FrameCalibration?,
    val content: FrameContent,
)

public enum class StreamState {
    STOPPED,
    STARTING,
    STREAMING,
    STALLED,
    ERROR,
}

public data class VideoStreamStatus(
    val state: StreamState,
    val modality: Modality?,
    val lastFrameAtMs: Long?,
    val framesReceived: Long,
    val simulated: Boolean,
    val detail: String? = null,
) {
    public companion object {
        public fun stopped(simulated: Boolean): VideoStreamStatus = VideoStreamStatus(StreamState.STOPPED, null, null, 0, simulated)
    }
}

/**
 * Live observation video, deliberately separate from the control/state channel.
 *
 * Implementations: [SimulatedVideoSource] (here) and, in a later Android module, a Media3/WebRTC adapter. Vendor SDKs
 * must stay inside those adapters; UI code only sees this interface.
 */
public interface VideoSource {
    public val frames: SharedFlow<VideoFrame>
    public val status: StateFlow<VideoStreamStatus>

    /** Switch the stream to [modality] for [sector]. Track IDs are unaffected by stream changes. */
    public fun bind(
        modality: Modality,
        sector: SectorId?,
    )

    public fun start()

    public fun stop()
}

/**
 * Deterministic synthetic video. Emits scene timestamps at [fps]; the UI paints a procedural panorama.
 * Calibration metadata follows [calibrationProvider] so degraded FUSED overlays can be demonstrated.
 */
public class SimulatedVideoSource(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val calibrationProvider: () -> CalibrationStatus,
    private val fps: Int = 15,
) : VideoSource {
    private val _frames = MutableSharedFlow<VideoFrame>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val frames: SharedFlow<VideoFrame> = _frames.asSharedFlow()
    private val _status = MutableStateFlow(VideoStreamStatus.stopped(simulated = true))
    override val status: StateFlow<VideoStreamStatus> = _status.asStateFlow()

    private var modality: Modality = Modality.VISIBLE
    private var job: Job? = null
    private var seq = 0L

    /** When true, frames stop arriving while status remains STREAMING-but-stale (exercises STALLED detection). */
    @Volatile public var simulateStall: Boolean = false

    override fun bind(
        modality: Modality,
        sector: SectorId?,
    ) {
        this.modality = modality
        _status.value = _status.value.copy(modality = modality)
    }

    override fun start() {
        if (job != null) return
        _status.value = _status.value.copy(state = StreamState.STARTING, modality = modality)
        job =
            scope.launch {
                val interval = 1000L / fps
                while (isActive) {
                    delay(interval)
                    if (simulateStall) continue
                    val now = clock.nowMs()
                    val calibration = calibrationProvider()
                    _frames.emit(
                        VideoFrame(
                            seq = ++seq,
                            timestampMs = now,
                            modality = modality,
                            widthPx = 3840,
                            heightPx = 1080,
                            calibration =
                                FrameCalibration(
                                    calibration,
                                    if (calibration ==
                                        CalibrationStatus.CALIBRATED
                                    ) {
                                        "sim-cal-1"
                                    } else {
                                        null
                                    },
                                ),
                            content = FrameContent.Synthetic(now),
                        ),
                    )
                    _status.value =
                        VideoStreamStatus(StreamState.STREAMING, modality, now, _status.value.framesReceived + 1, simulated = true)
                }
            }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _status.value = VideoStreamStatus.stopped(simulated = true)
    }
}
