package capture.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EnqueuePolicyTest {
    private val connected = ConnectionState(LinkStatus.CONNECTED, 10, 0, 0, 0, null, simulated = true)
    private val session = SessionState.Initial.copy(captureAvailable = true, captureUnavailableReason = null)

    private fun track(state: TrackState) =
        SubjectTrack(TrackId("S01"), EventId("EV"), state, NormalizedBox(0f, 0f, .1f, .1f), 0, 0, setOf(Modality.VISIBLE), "why")

    private fun blockedReason(
        track: SubjectTrack?,
        conn: ConnectionState = connected,
        s: SessionState = session,
        queue: List<QueueEntry> = emptyList(),
        rules: EnqueueRules = EnqueueRules(),
    ) = (EnqueuePolicy.evaluate(track, conn, s, queue, rules) as? EnqueueEligibility.Blocked)?.reason

    @Test fun activeTrackOnConnectedLinkIsAllowed() {
        assertIs<EnqueueEligibility.Allowed>(EnqueuePolicy.evaluate(track(TrackState.ACTIVE), connected, session, emptyList()))
    }

    @Test fun noSelectionIsBlocked() = assertEquals(EnqueueEligibility.BlockReason.NO_SELECTION, blockedReason(null))

    @Test fun linkDownAndSyncingBlockNewEnqueue() {
        assertEquals(
            EnqueueEligibility.BlockReason.LINK_DOWN,
            blockedReason(track(TrackState.ACTIVE), conn = connected.copy(status = LinkStatus.OFFLINE)),
        )
        assertEquals(
            EnqueueEligibility.BlockReason.SYNCING,
            blockedReason(track(TrackState.ACTIVE), conn = connected.copy(status = LinkStatus.SYNCING)),
        )
    }

    @Test fun uncertainAndLostTracksBlockedByDefaultWithReason() {
        assertEquals(EnqueueEligibility.BlockReason.TRACK_UNCERTAIN, blockedReason(track(TrackState.UNCERTAIN)))
        assertEquals(EnqueueEligibility.BlockReason.TRACK_LOST, blockedReason(track(TrackState.LOST)))
        val detail =
            (
                EnqueuePolicy.evaluate(
                    track(TrackState.UNCERTAIN),
                    connected,
                    session,
                    emptyList(),
                ) as EnqueueEligibility.Blocked
            ).detail
        assertEquals("S01 association uncertain: why", detail)
    }

    @Test fun occludedIsConfigurable() {
        assertEquals(EnqueueEligibility.BlockReason.TRACK_OCCLUDED, blockedReason(track(TrackState.OCCLUDED)))
        assertEquals(null, blockedReason(track(TrackState.OCCLUDED), rules = EnqueueRules(allowOccluded = true)))
    }

    @Test fun captureUnavailableBlocks() {
        assertEquals(
            EnqueueEligibility.BlockReason.CAPTURE_UNAVAILABLE,
            blockedReason(track(TrackState.ACTIVE), s = session.copy(captureAvailable = false)),
        )
    }

    @Test fun activeJobForSameTrackBlocksDuplicateButTerminalDoesNot() {
        fun entry(status: JobStatus) =
            QueueEntry(IdempotencyKey("key-00000001"), TrackId("S01"), EventId("EV"), null, status, 5, 10_000, 0, 0)
        assertEquals(
            EnqueueEligibility.BlockReason.ALREADY_QUEUED,
            blockedReason(track(TrackState.ACTIVE), queue = listOf(entry(JobStatus.PENDING_ACK))),
        )
        assertEquals(null, blockedReason(track(TrackState.ACTIVE), queue = listOf(entry(JobStatus.COMPLETED))))
    }
}
