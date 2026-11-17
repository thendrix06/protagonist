package capture.presentation

import capture.domain.EnqueueRules
import capture.domain.IdempotencyKey
import capture.domain.IdempotencyKeyFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** Operator-adjustable settings. Bounds are re-clamped to the backend [capture.domain.CapturePolicy] at send time. */
public data class OperatorSettings(
    val burstLimit: Int = 10,
    val ttlMs: Long = 30_000,
    /** Default off: occluded subjects cannot be queued until visible again. */
    val allowOccludedEnqueue: Boolean = false,
    /** RAM retention for unselected subject previews. Nothing is persisted to disk. */
    val previewRetentionMs: Long = 15_000,
    /** When calibration is degraded, keep drawing overlays but mark them approximate (true) or hide them (false). */
    val showUncalibratedOverlay: Boolean = true,
    val hidEnabled: Boolean = true,
) {
    init {
        require(burstLimit in BURST_RANGE) { "burstLimit out of range" }
        require(ttlMs in TTL_RANGE_MS) { "ttlMs out of range" }
        require(previewRetentionMs in PREVIEW_RETENTION_RANGE_MS) { "previewRetentionMs out of range" }
    }

    public fun enqueueRules(): EnqueueRules = EnqueueRules(allowOccluded = allowOccludedEnqueue)

    public companion object {
        public val BURST_RANGE: IntRange = 1..50
        public val TTL_RANGE_MS: LongRange = 5_000L..300_000L
        public val PREVIEW_RETENTION_RANGE_MS: LongRange = 0L..120_000L
    }
}

public interface SettingsStore {
    public val settings: StateFlow<OperatorSettings>

    public fun update(transform: (OperatorSettings) -> OperatorSettings)
}

public class InMemorySettingsStore(
    initial: OperatorSettings = OperatorSettings(),
) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<OperatorSettings> = state.asStateFlow()

    override fun update(transform: (OperatorSettings) -> OperatorSettings) {
        state.update(transform)
    }
}

public class UuidIdempotencyKeyFactory : IdempotencyKeyFactory {
    override fun next(): IdempotencyKey = IdempotencyKey(UUID.randomUUID().toString())
}

/** Deterministic keys for tests and the scripted demo. */
public class SequentialIdempotencyKeyFactory(
    private val prefix: String = "idem",
) : IdempotencyKeyFactory {
    private var n = 0

    override fun next(): IdempotencyKey = IdempotencyKey("%s-%08d".format(prefix, ++n))
}
