package capture.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

public data class TelemetrySnapshot(
    val uiStateComputations: Long = 0,
    val uiStateComputeAvgMicros: Long = 0,
    val uiStateComputeMaxMicros: Long = 0,
    val uiFrames: Long = 0,
    /** UI frames that took longer than [PerformanceTelemetry.JANK_THRESHOLD_MS]. */
    val uiJankFrames: Long = 0,
    val uiFrameWorstMs: Long = 0,
)

/**
 * Lightweight, privacy-neutral performance counters (durations and counts only), shown on System Status.
 * The Android layer feeds UI frame intervals; the state holder feeds reducer timings.
 */
public class PerformanceTelemetry {
    private val state = MutableStateFlow(TelemetrySnapshot())
    public val snapshot: StateFlow<TelemetrySnapshot> = state.asStateFlow()
    private var totalComputeNanos = 0L

    public fun recordUiStateCompute(nanos: Long) {
        state.update {
            val n = it.uiStateComputations + 1
            synchronized(this) { totalComputeNanos += nanos }
            it.copy(
                uiStateComputations = n,
                uiStateComputeAvgMicros = totalComputeNanos / n / 1000,
                uiStateComputeMaxMicros = maxOf(it.uiStateComputeMaxMicros, nanos / 1000),
            )
        }
    }

    public fun recordUiFrame(frameIntervalMs: Long) {
        state.update {
            it.copy(
                uiFrames = it.uiFrames + 1,
                uiJankFrames = it.uiJankFrames + if (frameIntervalMs > JANK_THRESHOLD_MS) 1 else 0,
                uiFrameWorstMs = maxOf(it.uiFrameWorstMs, frameIntervalMs),
            )
        }
    }

    public companion object {
        public const val JANK_THRESHOLD_MS: Long = 32
    }
}
