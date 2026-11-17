package capture.sim

import capture.domain.Clock
import capture.domain.Modality
import capture.domain.SensorHealth
import capture.domain.TrackState
import capture.protocol.Envelope
import capture.protocol.Payload
import capture.protocol.ProtocolCodec
import capture.protocol.ProtocolVersion
import capture.protocol.Stream
import capture.protocol.WireJobStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SimulatorServerTest {
    private class Run {
        var now = 1_763_400_000_000L
        val server = SimulatorServer(Clock { now })
        val received = ArrayList<String>()
        var clientSeq = 0L

        fun send(payload: Payload) {
            server.receive(
                ProtocolCodec.encode(
                    Envelope(ProtocolVersion.Current, ProtocolCodec.typeOf(payload), Stream.CONTROL, ++clientSeq, now, payload),
                ),
            )
            received += server.drain()
        }

        fun advance(ms: Long) {
            val end = now + ms
            while (now < end) {
                now += 100
                server.tick()
                received += server.drain()
            }
        }

        fun payloads() = received.map { ProtocolCodec.decode(it).payload }
    }

    private fun script(run: Run) {
        run.send(Payload.SnapshotRequest("connect"))
        run.advance(2_000)
        run.send(Payload.Enqueue("key-0000-0001", "S01", "EV-N1", 5, 30_000))
        run.advance(25_000)
        run.send(Payload.SetMode(capture.protocol.WireModality.THERMAL))
        run.advance(25_000)
    }

    @Test fun identicalInputsProduceIdenticalOutputs() {
        val a = Run().also(::script)
        val b = Run().also(::script)
        assertTrue(a.received.size > 100)
        assertEquals(a.received, b.received)
    }

    @Test fun stateSeqIsStrictlyMonotonic() {
        val run = Run().also(::script)
        val seqs = run.received.map { ProtocolCodec.decode(it).seq }
        assertEquals((1L..seqs.size).toList(), seqs)
    }

    @Test fun jobProgressesThroughBackendStatesToCompletion() {
        val run = Run()
        run.send(Payload.SnapshotRequest())
        run.send(Payload.Enqueue("key-0000-0001", "S01", "EV-N1", 5, 30_000))
        run.advance(5_000)
        val statuses = run.payloads().filterIsInstance<Payload.JobUpdate>().map { it.job.status }.distinct()
        assertEquals(listOf(WireJobStatus.QUEUED, WireJobStatus.ACQUIRING, WireJobStatus.CAPTURING, WireJobStatus.COMPLETED), statuses)
        val last = run.payloads().filterIsInstance<Payload.JobUpdate>().last().job
        assertEquals(5, last.framesCaptured)
    }

    @Test fun duplicateIdempotencyKeyCreatesNoSecondJob() {
        val run = Run()
        run.send(Payload.Enqueue("key-0000-0001", "S01", "EV-N1", 5, 30_000))
        run.send(Payload.Enqueue("key-0000-0001", "S01", "EV-N1", 5, 30_000))
        run.advance(500)
        assertEquals(1, run.server.jobCount)
        val acks = run.payloads().filterIsInstance<Payload.CommandAck>()
        assertEquals(listOf("J-0001", "J-0001"), acks.map { it.jobId })
    }

    @Test fun scriptedOcclusionLossAndAmbiguousReacquisitionKeepIds() {
        val run = Run()
        run.send(Payload.SnapshotRequest())
        run.advance(50_000)
        val updates = run.payloads().filterIsInstance<Payload.TrackUpdate>().map { it.track }
        val s01States = updates.filter { it.trackId == "S01" }.map { it.state.name }.distinct()
        assertEquals(listOf("ACTIVE", "OCCLUDED"), s01States.take(2))
        val s03States = updates.filter { it.trackId == "S03" }.map { it.state.name }.distinct()
        assertEquals(listOf("ACTIVE", "LOST", "UNCERTAIN"), s03States)
        assertEquals(setOf("S01", "S02", "S03", "S04", "S05"), updates.map { it.trackId }.toSet(), "only S05 is new; no re-labelling")
    }

    @Test fun invalidVisibleSensorMakesCaptureUnavailableAndRejectsEnqueue() {
        val run = Run()
        run.server.setSensorHealth(Modality.VISIBLE, SensorHealth.INVALID, "No frames")
        run.send(Payload.Enqueue("key-0000-0001", "S01", "EV-N1", 5, 30_000))
        run.advance(500)
        val ack = run.payloads().filterIsInstance<Payload.CommandAck>().single()
        assertEquals(false, ack.accepted)
        val session = run.payloads().filterIsInstance<Payload.SessionUpdate>().last()
        assertEquals(false, session.captureAvailable)
    }

    @Test fun forcedLossDuringAcquisitionFailsTheJobWithReason() {
        val run = Run()
        run.send(Payload.Enqueue("key-0000-0001", "S01", "EV-N1", 5, 30_000))
        run.advance(400)
        run.server.forceTrackState("S01", TrackState.LOST, "test")
        run.advance(500)
        val job = run.payloads().filterIsInstance<Payload.JobUpdate>().last().job
        assertEquals(WireJobStatus.FAILED, job.status)
        assertTrue(job.reason!!.contains("lost"))
    }

    @Test fun snapshotReportsLastClientSeqProcessed() {
        val run = Run()
        run.send(Payload.Enqueue("key-0000-0001", "S01", "EV-N1", 5, 30_000))
        run.send(Payload.SnapshotRequest())
        val snap = run.payloads().filterIsInstance<Payload.Snapshot>().single()
        assertEquals(2, snap.lastClientSeqProcessed)
        assertIs<Payload.Snapshot>(snap)
    }
}
