package capture.presentation

import capture.domain.CalibrationStatus
import capture.domain.EnqueueEligibility.BlockReason
import capture.domain.JobId
import capture.domain.JobStatus
import capture.domain.LinkStatus
import capture.domain.Modality
import capture.domain.SensorHealth
import capture.domain.TrackId
import capture.domain.TrackState
import capture.hid.ControllerInput
import capture.hid.HidResult
import capture.hid.Screen
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Automated acceptance scenarios A–E and H from the execution brief, run end-to-end against the deterministic
 * simulator. (F and G layout/touch-target checks live in the Android UI test suite; G's label/contrast parts are in
 * AccessibilityTokensTest.)
 */
class AcceptanceScenariosTest {
    private val s01 = TrackId("S01")
    private val s02 = TrackId("S02")

    @Test fun `A - selecting S01 highlights everywhere and leaves the queue unchanged, Add creates exactly one acked job`() =
        runTest {
            val h = SimHarness(this).start()
            assertEquals(LinkStatus.CONNECTED, h.ui.link.status)

            h.holder.onSubjectTapped(s01)
            h.advance(300)
            assertEquals("S01", h.ui.selected?.label)
            assertEquals(listOf("S01"), h.ui.subjects.filter { it.selected }.map { it.label }, "single highlight")
            assertTrue(h.subject("S01").contentDescription.contains("selected"))
            assertTrue(h.ui.queue.isEmpty(), "selection must not create a job")
            assertEquals(0, h.repo.server.jobCount)
            assertEquals(0, h.repo.server.enqueueFramesReceived)

            h.holder.onAddToQueue()
            h.advance(1) // command sent, ack not yet delivered (simulated ack delay 300 ms)
            val pending = h.ui.queue.single()
            assertEquals(JobStatus.PENDING_ACK, pending.status, "optimistic state is pending, not queued")
            assertNull(pending.jobId)
            assertNull(pending.progress, "no invented progress")

            h.advance(400)
            val acked = h.ui.queue.single()
            assertEquals(JobId("J-0001"), acked.jobId)
            assertTrue(acked.status in setOf(JobStatus.QUEUED, JobStatus.ACQUIRING))
            assertEquals("S01", acked.trackLabel, "same ID in queue as in overlay/strip")
            assertEquals(1, h.repo.server.jobCount)

            h.advance(4_000)
            val done = h.ui.queue.single()
            assertEquals(JobStatus.COMPLETED, done.status)
            assertEquals("10/10", done.progress, "frame count is what the backend reported")
            assertEquals(1, h.repo.server.enqueueFramesReceived)
        }

    @Test fun `A - double tap on Add for the same subject yields one job`() =
        runTest {
            val h = SimHarness(this).start()
            h.holder.onSubjectTapped(s01)
            h.advance(300)
            h.holder.onAddToQueue()
            h.holder.onAddToQueue()
            h.advance(1_000)
            assertEquals(1, h.repo.server.jobCount)
            assertEquals(1, h.ui.queue.size)
        }

    @Test fun `B - rapidly authorizing S01 to S04 creates exactly four distinct jobs while selection continues`() =
        runTest {
            val h = SimHarness(this).start()
            for (id in listOf("S01", "S02", "S03", "S04")) {
                h.holder.onSubjectTapped(TrackId(id))
                h.holder.onAddToQueue() // no time passes between authorizations
            }
            h.advance(1)
            assertEquals(listOf("S01", "S02", "S03", "S04"), h.ui.queue.map { it.trackLabel })
            assertTrue(h.ui.queue.all { it.status == JobStatus.PENDING_ACK })

            // Operator keeps selecting while statuses update asynchronously.
            h.holder.onSubjectTapped(s02)
            h.advance(500)
            assertEquals("S02", h.ui.selected?.label)
            val jobIds = h.ui.queue.map { it.jobId }
            assertEquals(4, jobIds.filterNotNull().toSet().size, "four distinct backend job IDs")
            assertEquals(4, h.repo.server.jobCount)
            assertEquals(4, h.repo.server.enqueueFramesReceived)

            val seen = mutableSetOf<JobStatus>()
            repeat(40) {
                h.advance(250)
                h.ui.queue.forEach { seen += it.status }
                assertEquals("S02", h.ui.selected?.label, "queue progress never changes selection")
            }
            assertTrue(seen.containsAll(listOf(JobStatus.QUEUED, JobStatus.ACQUIRING, JobStatus.CAPTURING, JobStatus.COMPLETED)))
            assertEquals(4, h.ui.queue.size)
        }

    @Test fun `C - switching Visible to Thermal to Fused keeps S01 identity, degraded overlay is marked`() =
        runTest {
            val h = SimHarness(this).start()
            h.holder.onSubjectTapped(s01)
            h.advance(300)
            val idsBefore = h.ids()
            for (mode in listOf(Modality.VISIBLE, Modality.THERMAL, Modality.FUSED)) {
                h.holder.onModeSelected(mode)
                h.advance(1)
                h.advance(300)
                assertEquals(mode, h.ui.modality, "backend confirmed $mode")
                assertNull(h.ui.pendingModality)
                assertEquals(idsBefore, h.ids(), "track IDs unchanged across modes")
                assertEquals("S01", h.ui.selected?.label)
                assertEquals(mode, h.video.status.value.modality, "video stream rebound to $mode")
            }
            assertTrue(h.ui.overlay.calibrated)
            assertNull(h.ui.overlay.warning)

            h.repo.setCalibration(CalibrationStatus.UNAVAILABLE)
            h.advance(300)
            assertFalse(h.ui.overlay.calibrated)
            assertTrue(h.ui.overlay.visible, "overlay optional but shown by default")
            assertTrue(h.ui.overlay.warning!!.contains("UNCALIBRATED"))
            assertEquals("S01", h.ui.selected?.label)

            h.holder.updateSettings { it.copy(showUncalibratedOverlay = false) }
            h.advance(300)
            assertFalse(h.ui.overlay.visible)
        }

    @Test fun `D - occlusion keeps S01, ambiguous reacquisition is UNCERTAIN and never auto-merged`() =
        runTest {
            val h = SimHarness(this).start()
            assertEquals(listOf("S01", "S02", "S03", "S04"), h.ids())
            h.holder.onSubjectTapped(s01)

            h.advanceTo(21_000)
            assertEquals(TrackState.OCCLUDED, h.subject("S01").state)
            assertEquals(listOf("S01", "S02", "S03", "S04"), h.ids(), "no invented ID during occlusion")
            assertEquals("S01", h.ui.selected?.label)
            assertFalse(h.ui.enqueue.enabled)
            assertEquals(BlockReason.TRACK_OCCLUDED, h.ui.enqueue.blockCode)

            h.advanceTo(27_000)
            assertEquals(TrackState.ACTIVE, h.subject("S01").state, "same ID after occlusion")
            assertTrue(h.ui.enqueue.enabled)

            h.advanceTo(36_000)
            assertEquals(TrackState.LOST, h.subject("S03").state)
            h.advanceTo(41_000)
            val s03 = h.subject("S03")
            assertEquals(TrackState.UNCERTAIN, s03.state)
            assertTrue(s03.reason!!.contains("Ambiguous"))
            h.holder.onSubjectTapped(TrackId("S03"))
            h.advance(300)
            assertFalse(h.ui.enqueue.enabled)
            assertEquals(BlockReason.TRACK_UNCERTAIN, h.ui.enqueue.blockCode)
            assertTrue(h.ui.enqueue.blockedReason!!.contains("Ambiguous"), "reason shown to operator")

            // Explicit attempt is still refused and nothing reaches the backend.
            h.holder.onAddToQueue()
            h.advance(500)
            assertEquals(0, h.repo.server.enqueueFramesReceived)

            h.advanceTo(46_000)
            assertEquals(listOf("S01", "S02", "S03", "S04", "S05"), h.ids(), "new person gets a new ID")
            h.advanceTo(60_000)
            assertEquals(TrackState.UNCERTAIN, h.subject("S03").state, "no automatic merge")
        }

    @Test fun `E - disconnect after command, then reconnect, reconciles by idempotency key with no duplicate enqueue`() =
        runTest {
            val h = SimHarness(this).start()
            h.holder.onSubjectTapped(s01)
            h.advance(300)

            h.repo.dropLinkAfterNextCommand()
            h.holder.onAddToQueue()
            h.advance(1)
            assertEquals(1, h.repo.server.enqueueFramesReceived, "backend received the command")
            assertEquals(LinkStatus.OFFLINE, h.ui.link.status)
            assertEquals(JobStatus.UNCONFIRMED, h.ui.queue.single().status, "outcome unknown, not claimed")
            assertFalse(h.ui.enqueue.enabled)
            assertEquals(BlockReason.LINK_DOWN, h.ui.enqueue.blockCode)

            // Operator tries again on S02 while the link is down: refused locally, nothing sent.
            h.holder.onSubjectTapped(s02)
            h.holder.onAddToQueue()
            h.advance(2_000)
            assertEquals(1, h.repo.server.enqueueFramesReceived)

            h.repo.restoreLink()
            h.advance(500)
            assertEquals(LinkStatus.CONNECTED, h.ui.link.status)
            val reconciled = h.ui.queue.single()
            assertEquals(JobId("J-0001"), reconciled.jobId, "matched to the backend job by idempotency key")
            assertNotEquals(JobStatus.UNCONFIRMED, reconciled.status)
            assertEquals(1, h.repo.server.jobCount)
            assertEquals(1, h.repo.server.enqueueFramesReceived, "never replayed blindly")
            assertTrue(h.repo.diagnostics.value.snapshotsApplied >= 2)
        }

    @Test fun `E - seq gap while connected triggers snapshot resync`() =
        runTest {
            val h = SimHarness(this).start()
            h.repo.injectSeqGap()
            h.advance(1_000)
            assertEquals(1, h.repo.diagnostics.value.seqGapsDetected)
            assertEquals(LinkStatus.CONNECTED, h.ui.link.status)
            assertEquals(listOf("S01", "S02", "S03", "S04"), h.ids())
        }

    @Test fun `H - invalid mock sensor shows DEGRADED and never a live or capture indicator`() =
        runTest {
            val h = SimHarness(this).start()
            assertEquals(FeedIndicator.SIMULATED, h.ui.feed.indicator, "simulator is never presented as LIVE")

            h.repo.setSensorHealth(Modality.THERMAL, SensorHealth.INVALID, "No frames")
            h.holder.onModeSelected(Modality.THERMAL)
            h.advance(500)
            assertEquals(FeedIndicator.DEGRADED, h.ui.feed.indicator)
            assertEquals("DEGRADED", h.ui.feed.label)
            assertTrue(h.ui.alerts.any { it.text.contains("Thermal sensor INVALID") })

            h.holder.onModeSelected(Modality.FUSED)
            h.advance(500)
            assertEquals(FeedIndicator.DEGRADED, h.ui.feed.indicator, "fused depends on thermal")

            // Capture camera invalid: backend reports capture unavailable; an in-flight job fails, never completes.
            h.holder.onModeSelected(Modality.VISIBLE)
            h.holder.onSubjectTapped(s01)
            h.advance(300)
            h.holder.onAddToQueue()
            h.advance(1_200)
            h.repo.setSensorHealth(Modality.VISIBLE, SensorHealth.INVALID, "Shutter fault")
            h.advance(1_000)
            assertEquals(FeedIndicator.DEGRADED, h.ui.feed.indicator)
            assertFalse(h.ui.enqueue.enabled)
            assertEquals(BlockReason.CAPTURE_UNAVAILABLE, h.ui.enqueue.blockCode)
            val job = h.ui.queue.single()
            assertEquals(JobStatus.FAILED, job.status)
            assertEquals("Capture sensor invalid", job.reason)
        }

    @Test fun `H - stalled video is shown as NO VIDEO rather than live`() =
        runTest {
            val h = SimHarness(this).start()
            h.video.simulateStall = true
            h.advance(2_000)
            assertEquals(FeedIndicator.STALE, h.ui.feed.indicator)
        }

    @Test fun `HID - navigation selects in strip order, enqueue button authorizes once, debounced`() =
        runTest {
            val h = SimHarness(this).start()
            assertIs<HidResult.Command>(h.holder.onControllerInput(ControllerInput.NAV_RIGHT, Screen.CAPTURE))
            h.advance(400)
            assertEquals("S01", h.ui.selected?.label)
            h.holder.onControllerInput(ControllerInput.NAV_RIGHT, Screen.CAPTURE)
            h.advance(400)
            assertEquals("S02", h.ui.selected?.label)
            assertEquals(0, h.repo.server.enqueueFramesReceived, "navigation never authorizes")

            h.holder.onControllerInput(ControllerInput.ENQUEUE, Screen.CAPTURE)
            val bounce = h.holder.onControllerInput(ControllerInput.ENQUEUE, Screen.CAPTURE)
            assertEquals(HidResult.Ignored(HidResult.Reason.DEBOUNCED), bounce)
            h.advance(1_000)
            assertEquals(1, h.repo.server.enqueueFramesReceived)
            assertEquals("S02", h.ui.queue.single().trackLabel)

            assertEquals(
                HidResult.Ignored(HidResult.Reason.NOT_AVAILABLE_ON_SCREEN),
                h.holder.onControllerInput(ControllerInput.ENQUEUE, Screen.SETTINGS),
            )
        }

    @Test fun `stable ordering - strip order never changes as subjects move or change state`() =
        runTest {
            val h = SimHarness(this).start()
            val orders = mutableSetOf<List<String>>()
            repeat(120) {
                h.advance(500)
                orders += h.ids().filter { it != "S05" }
            }
            assertEquals(setOf(listOf("S01", "S02", "S03", "S04")), orders)
        }

    @Test fun `sector switch shows that sector's subjects and keeps the queue`() =
        runTest {
            val h = SimHarness(this).start()
            h.holder.onSubjectTapped(s01)
            h.advance(100)
            h.holder.onAddToQueue()
            h.advance(500)
            h.holder.onSectorSelected(capture.sim.SimScenario.EAST)
            h.advance(500)
            assertEquals(listOf("S06", "S07"), h.ids())
            assertNull(h.ui.selected, "selection cleared when the track leaves; never moved to another subject")
            assertEquals(1, h.ui.queue.size)
        }
}
