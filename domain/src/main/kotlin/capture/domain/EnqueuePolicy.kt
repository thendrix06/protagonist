package capture.domain

/** Why the ADD TO QUEUE action is (un)available. Reasons are shown verbatim to the operator. */
public sealed interface EnqueueEligibility {
    public data object Allowed : EnqueueEligibility

    public data class Blocked(
        val reason: BlockReason,
        val detail: String,
    ) : EnqueueEligibility

    public enum class BlockReason {
        NO_SELECTION,
        LINK_DOWN,
        SYNCING,
        CAPTURE_UNAVAILABLE,
        TRACK_UNCERTAIN,
        TRACK_LOST,
        TRACK_OCCLUDED,
        ALREADY_QUEUED,
    }
}

/** Operator-side guard rails. Defaults are conservative and documented in the README. */
public data class EnqueueRules(
    /** Uncertain tracks are blocked by default to avoid capturing an unconfirmed association. */
    val allowUncertain: Boolean = false,
    /** Lost tracks are blocked by default. */
    val allowLost: Boolean = false,
    /** Occluded tracks are blocked by default; enable to let the backend acquire on reappearance. */
    val allowOccluded: Boolean = false,
)

/**
 * Pure decision function for enqueue gating. Kept in the domain so the UI, HID controller and tests share
 * exactly one definition.
 */
public object EnqueuePolicy {
    public fun evaluate(
        track: SubjectTrack?,
        connection: ConnectionState,
        session: SessionState,
        queue: List<QueueEntry>,
        rules: EnqueueRules = EnqueueRules(),
    ): EnqueueEligibility {
        if (track == null) return blocked(EnqueueEligibility.BlockReason.NO_SELECTION, "Select a subject first")
        when (connection.status) {
            LinkStatus.OFFLINE, LinkStatus.CONNECTING -> {
                return blocked(EnqueueEligibility.BlockReason.LINK_DOWN, "Link down — new captures disabled")
            }

            LinkStatus.SYNCING -> {
                return blocked(EnqueueEligibility.BlockReason.SYNCING, "Reconciling with backend")
            }

            LinkStatus.CONNECTED -> {}
        }
        if (!session.captureAvailable) {
            return blocked(
                EnqueueEligibility.BlockReason.CAPTURE_UNAVAILABLE,
                session.captureUnavailableReason ?: "Backend reports capture unavailable",
            )
        }
        when (track.state) {
            TrackState.UNCERTAIN -> {
                if (!rules.allowUncertain) {
                    return blocked(
                        EnqueueEligibility.BlockReason.TRACK_UNCERTAIN,
                        "${track.trackId} association uncertain" + (track.stateReason?.let { ": $it" } ?: ""),
                    )
                }
            }

            TrackState.LOST -> {
                if (!rules.allowLost) {
                    return blocked(EnqueueEligibility.BlockReason.TRACK_LOST, "${track.trackId} lost")
                }
            }

            TrackState.OCCLUDED -> {
                if (!rules.allowOccluded) {
                    return blocked(EnqueueEligibility.BlockReason.TRACK_OCCLUDED, "${track.trackId} occluded")
                }
            }

            TrackState.ACTIVE -> {}
        }
        val active = queue.firstOrNull { it.trackId == track.trackId && !it.status.terminal }
        if (active != null) {
            return blocked(
                EnqueueEligibility.BlockReason.ALREADY_QUEUED,
                "${track.trackId} already has a ${active.status.name.lowercase().replace('_', ' ')} job",
            )
        }
        return EnqueueEligibility.Allowed
    }

    private fun blocked(
        reason: EnqueueEligibility.BlockReason,
        detail: String,
    ) = EnqueueEligibility.Blocked(reason, detail)
}
