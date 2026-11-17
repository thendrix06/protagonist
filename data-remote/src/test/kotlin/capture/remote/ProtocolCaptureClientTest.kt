package capture.remote

import capture.domain.Clock
import capture.domain.CommandResult
import capture.domain.EnqueueRequest
import capture.domain.EventId
import capture.domain.IdempotencyKey
import capture.domain.JobId
import capture.domain.JobStatus
import capture.domain.LinkStatus
import capture.domain.TrackId
import capture.protocol.Envelope
import capture.protocol.Payload
import capture.protocol.ProtocolCodec
import capture.protocol.ProtocolVersion
import capture.protocol.Stream
import capture.protocol.WireCalibration
import capture.protocol.WireCommand
import capture.protocol.WireJob
import capture.protocol.WireJobStatus
import capture.protocol.WireModality
import capture.protocol.WirePolicy
import capture.protocol.WireSector
import capture.protocol.WireTrack
import capture.protocol.WireTrackState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests of the protocol client against the mock transport. The test plays the server, so every edge
 * case (gaps, duplicates, lost acks, reconnects, version mismatch) is driven precisely.
 */
class ProtocolCaptureClientTest {
    private class Harness(
        val scope: TestScope,
    ) {
        val channel = InMemoryControlChannel()
        val clock = Clock { 1_000_000L + scope.testScheduler.currentTime }
        val client = ProtocolCaptureClient(channel, scope.backgroundScope, clock, simulated = false)
        var serverSeq = 0L

        fun sentPayloads(): List<Payload> = channel.sentFrames.map { ProtocolCodec.decode(it).payload }

        fun sentEnqueues() = sentPayloads().filterIsInstance<Payload.Enqueue>()

        suspend fun deliver(
            payload: Payload,
            seq: Long = ++serverSeq,
        ) {
            channel.deliver(
                ProtocolCodec.encode(
                    Envelope(ProtocolVersion.Current, ProtocolCodec.typeOf(payload), Stream.STATE, seq, clock.nowMs(), payload),
                ),
            )
            scope.testScheduler.runCurrent()
        }

        fun track(
            id: String,
            state: WireTrackState = WireTrackState.ACTIVE,
        ) = WireTrack(id, "EV-1", state, 1, 2, listOf(WireModality.VISIBLE))

        fun job(
            key: String,
            jobId: String,
            status: WireJobStatus,
            track: String = "S01",
        ) = WireJob(jobId, key, track, "EV-1", status, 10, 30_000, 5, 6, cancellable = true)

        fun snapshot(
            lastClientSeq: Long,
            tracks: List<WireTrack> = listOf(track("S01"), track("S02")),
            jobs: List<WireJob> = emptyList(),
        ) = Payload.Snapshot(
            sessionId = "s",
            lastClientSeqProcessed = lastClientSeq,
            sectors = listOf(WireSector("N", "North")),
            modality = WireModality.VISIBLE,
            captureAvailable = true,
            policy = WirePolicy(20, 5_000, 120_000, true),
            tracks = tracks,
            jobs = jobs,
            sensors = Payload.SensorUpdate(emptyList(), WireCalibration.CALIBRATED),
            activeSectorId = "N",
        )

        suspend fun connectAndSync() {
            client.start()
            scope.testScheduler.runCurrent()
            assertIs<Payload.SnapshotRequest>(sentPayloads().last())
            deliver(snapshot(lastClientSeq = channel.sentFrames.size.toLong()))
            assertEquals(LinkStatus.CONNECTED, client.connection.value.status)
        }

        fun request(
            track: String,
            key: String,
        ) = EnqueueRequest(TrackId(track), EventId("EV-1"), IdempotencyKey(key), 10, 30_000)
    }

    @Test fun selectionIsLocalAndSendsNothing() =
        runTest {
            val h = Harness(this)
            h.connectAndSync()
            val before = h.channel.sentFrames.size
            h.client.selectTrack(TrackId("S01"))
            assertEquals(TrackId("S01"), h.client.selectedTrackId.value)
            assertEquals(before, h.channel.sentFrames.size, "selection must not transmit")
            assertTrue(h.client.queue.value.isEmpty())
        }

    @Test fun enqueueIsPendingUntilAckThenQueued() =
        runTest {
            val h = Harness(this)
            h.connectAndSync()
            assertEquals(CommandResult.Sent, h.client.authorizeEnqueue(h.request("S01", "key-0000-0001")))
            val pending = h.client.queue.value.single()
            assertEquals(JobStatus.PENDING_ACK, pending.status)
            assertNull(pending.jobId, "no job id before the backend assigns one")
            val clientSeq = ProtocolCodec.decode(h.channel.sentFrames.last()).seq
            h.deliver(Payload.CommandAck(clientSeq, WireCommand.ENQUEUE, true, "key-0000-0001", "J-9"))
            val acked = h.client.queue.value.single()
            assertEquals(JobStatus.QUEUED, acked.status)
            assertEquals(JobId("J-9"), acked.jobId)
        }

    @Test fun duplicateAuthorizationOfSameTrackIsRefusedLocally() =
        runTest {
            val h = Harness(this)
            h.connectAndSync()
            h.client.authorizeEnqueue(h.request("S01", "key-0000-0001"))
            val second = h.client.authorizeEnqueue(h.request("S01", "key-0000-0002"))
            assertIs<CommandResult.Refused>(second)
            assertEquals(1, h.sentEnqueues().size)
        }

    @Test fun rejectedAckIsShownAsRejectedWithBackendReason() =
        runTest {
            val h = Harness(this)
            h.connectAndSync()
            h.client.authorizeEnqueue(h.request("S01", "key-0000-0001"))
            val seq = ProtocolCodec.decode(h.channel.sentFrames.last()).seq
            h.deliver(Payload.CommandAck(seq, WireCommand.ENQUEUE, false, "key-0000-0001", reason = "Policy: sector closed"))
            val e = h.client.queue.value.single()
            assertEquals(JobStatus.REJECTED, e.status)
            assertEquals("Policy: sector closed", e.reason)
        }

    @Test fun seqGapDiscardsDeltasAndRequestsSnapshot() =
        runTest {
            val h = Harness(this)
            h.connectAndSync()
            h.deliver(Payload.TrackUpdate(h.track("S01", WireTrackState.OCCLUDED)), seq = h.serverSeq + 2) // gap
            h.serverSeq += 2
            assertEquals(LinkStatus.SYNCING, h.client.connection.value.status)
            assertEquals(1, h.client.diagnostics.value.seqGapsDetected)
            assertIs<Payload.SnapshotRequest>(h.sentPayloads().last())
            assertEquals("ACTIVE", h.client.tracks.value.first().state.name, "delta after a gap must not be applied")
            // Further deltas are ignored until the snapshot arrives.
            h.deliver(Payload.TrackRemoved("S02", "EV-1"))
            assertEquals(2, h.client.tracks.value.size)
            h.deliver(
                h.snapshot(lastClientSeq = h.channel.sentFrames.size.toLong(), tracks = listOf(h.track("S01", WireTrackState.OCCLUDED))),
            )
            assertEquals(LinkStatus.CONNECTED, h.client.connection.value.status)
            assertEquals("OCCLUDED", h.client.tracks.value.single().state.name)
        }

    @Test fun duplicateServerMessagesAreDropped() =
        runTest {
            val h = Harness(this)
            h.connectAndSync()
            h.deliver(Payload.TrackRemoved("S02", "EV-1"))
            h.deliver(Payload.TrackUpdate(h.track("S02")), seq = h.serverSeq) // replay of same seq
            assertEquals(listOf("S01"), h.client.tracks.value.map { it.trackId.value })
            assertEquals(1, h.client.diagnostics.value.duplicatesDropped)
        }

    @Test fun linkLossMakesPendingUnconfirmedAndBlocksNewEnqueue() =
        runTest {
            val h = Harness(this)
            h.connectAndSync()
            h.client.authorizeEnqueue(h.request("S01", "key-0000-0001"))
            h.channel.forceState(ChannelState.CLOSED)
            testScheduler.runCurrent()
            assertEquals(JobStatus.UNCONFIRMED, h.client.queue.value.single().status)
            assertEquals(LinkStatus.OFFLINE, h.client.connection.value.status)
            assertIs<CommandResult.Refused>(h.client.authorizeEnqueue(h.request("S02", "key-0000-0002")))
        }

    @Test fun reconnectReconcilesByIdempotencyKeyWithoutReplay() =
        runTest {
            val h = Harness(this)
            h.connectAndSync()
            h.client.authorizeEnqueue(h.request("S01", "key-0000-0001"))
            val enqueueSeq = ProtocolCodec.decode(h.channel.sentFrames.last()).seq
            h.channel.forceState(ChannelState.CLOSED) // ack lost
            testScheduler.runCurrent()
            h.channel.forceState(ChannelState.OPEN)
            testScheduler.runCurrent()
            assertIs<Payload.SnapshotRequest>(h.sentPayloads().last(), "reconnect requests a snapshot")
            h.deliver(
                h.snapshot(lastClientSeq = enqueueSeq + 1, jobs = listOf(h.job("key-0000-0001", "J-1", WireJobStatus.ACQUIRING))),
                seq = h.serverSeq + 5,
            )
            h.serverSeq += 5
            val e = h.client.queue.value.single()
            assertEquals(JobStatus.ACQUIRING, e.status)
            assertEquals(JobId("J-1"), e.jobId)
            assertEquals(1, h.sentEnqueues().size, "enqueue must never be replayed after reconnect")
        }

    @Test fun reconnectWithoutServerRecordMarksNotReceivedAndDoesNotResend() =
        runTest {
            val h = Harness(this)
            h.connectAndSync()
            h.client.authorizeEnqueue(h.request("S01", "key-0000-0001"))
            h.channel.forceState(ChannelState.CLOSED)
            testScheduler.runCurrent()
            h.channel.forceState(ChannelState.OPEN)
            testScheduler.runCurrent()
            h.deliver(h.snapshot(lastClientSeq = 1, jobs = emptyList()), seq = h.serverSeq + 3)
            h.serverSeq += 3
            val e = h.client.queue.value.single()
            assertEquals(JobStatus.NOT_RECEIVED, e.status)
            assertEquals(1, h.sentEnqueues().size)
            // Operator may now explicitly re-authorize (a new explicit action, new key).
            assertEquals(CommandResult.Sent, h.client.authorizeEnqueue(h.request("S01", "key-0000-0003")))
        }

    @Test fun ackTimeoutMarksUnconfirmedAndRequestsSnapshot() =
        runTest {
            val h = Harness(this)
            h.connectAndSync()
            h.client.authorizeEnqueue(h.request("S01", "key-0000-0001"))
            testScheduler.advanceTimeBy(5_001)
            testScheduler.runCurrent()
            assertEquals(JobStatus.UNCONFIRMED, h.client.queue.value.single().status)
            assertIs<Payload.SnapshotRequest>(h.sentPayloads().last())
            assertEquals(1, h.sentEnqueues().size)
        }

    @Test fun unsupportedMajorVersionBlocksCommands() =
        runTest {
            val h = Harness(this)
            h.connectAndSync()
            h.channel.deliver("""{"protocolVersion":"2.0","type":"heartbeat","stream":"heartbeat","seq":1,"timestampMs":1,"payload":{}}""")
            testScheduler.runCurrent()
            assertEquals(LinkStatus.SYNCING, h.client.connection.value.status)
            assertTrue(h.client.connection.value.detail!!.contains("2.0"))
            assertIs<CommandResult.Refused>(h.client.authorizeEnqueue(h.request("S01", "key-0000-0001")))
        }

    @Test fun removedSelectedTrackClearsSelectionInsteadOfReassigning() =
        runTest {
            val h = Harness(this)
            h.connectAndSync()
            h.client.selectTrack(TrackId("S01"))
            h.deliver(Payload.TrackRemoved("S01", "EV-1"))
            assertNull(h.client.selectedTrackId.value)
        }

    @Test fun insecureEndpointIsRejected() {
        val ex = runCatching { RemoteEndpoint("ws://backend.example/control") }.exceptionOrNull()
        assertIs<IllegalArgumentException>(ex)
        RemoteEndpoint("wss://backend.example/control")
    }

    @Test fun remoteRepositoryWiresOverAnyChannelWithSamCredentialProvider() =
        runTest {
            // Same construction as AppContainer uses for the production path.
            val credentials = CredentialProvider { null }
            kotlin.test.assertNull(credentials.bearerToken())
            val channel = InMemoryControlChannel()
            val repo = RemoteCaptureRepository.overChannel(channel, backgroundScope, Clock { 0L })
            repo.start()
            testScheduler.runCurrent()
            assertIs<Payload.SnapshotRequest>(ProtocolCodec.decode(channel.sentFrames.single()).payload)
            assertEquals(false, repo.connection.value.simulated)
        }
}
