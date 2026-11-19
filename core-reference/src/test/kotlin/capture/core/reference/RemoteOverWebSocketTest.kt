package capture.core.reference

import capture.domain.Clock
import capture.domain.CommandResult
import capture.domain.EnqueueRequest
import capture.domain.IdempotencyKey
import capture.domain.JobStatus
import capture.domain.LinkStatus
import capture.domain.TrackId
import capture.remote.RemoteCaptureRepository
import capture.remote.RemoteEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end over a real socket: production RemoteCaptureRepository + WebSocketControlChannel (OkHttp) against the
 * reference server. Real time, so assertions poll with timeouts.
 */
class RemoteOverWebSocketTest {
    private val token = "test-token-0123456789"
    private val server = ReferenceCoreServer(token, port = 0)
    private val port = server.start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() {
        scope.cancel()
        server.stop()
    }

    private fun repo(bearer: String = token): RemoteCaptureRepository =
        RemoteCaptureRepository
            .create(
                endpoint = RemoteEndpoint("ws://127.0.0.1:$port/control", allowInsecureForLoopbackTests = true, initialBackoffMs = 200),
                credentials = { bearer },
                scope = scope,
                clock = Clock { System.currentTimeMillis() },
                rules = { capture.domain.EnqueueRules() },
            ).also { it.start() }

    private suspend fun eventually(
        what: String,
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(50) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    @Test fun connectsWithTokenSyncsAndCompletesAJob() =
        runBlocking {
            val repo = repo()
            eventually("CONNECTED") { repo.connection.value.status == LinkStatus.CONNECTED }
            eventually("S01..S04 tracked") { repo.tracks.value.map { it.trackId.value }.containsAll(listOf("S01", "S02", "S03", "S04")) }
            assertEquals(false, repo.connection.value.simulated, "remote adapter reports a real (non-simulated) link")

            repo.selectTrack(TrackId("S01"))
            delay(300)
            assertEquals(0, server.withSimulator { it.enqueueFramesReceived }, "selection never reaches the backend")

            val s01 = repo.tracks.value.first { it.trackId.value == "S01" }
            val result = repo.authorizeEnqueue(EnqueueRequest(s01.trackId, s01.eventId, IdempotencyKey("ws-key-00000001"), 5, 30_000))
            assertEquals(CommandResult.Sent, result)
            eventually("job acknowledged with server jobId") { repo.queue.value.singleOrNull()?.jobId != null }
            eventually("job completed") { repo.queue.value.single().status == JobStatus.COMPLETED }
            assertEquals(5, repo.queue.value.single().framesCaptured)
            assertEquals(1, server.withSimulator { it.enqueueFramesReceived })
            repo.stop()
        }

    @Test fun serverSideLinkDropReconnectsAndReconcilesWithoutResend() =
        runBlocking {
            val repo = repo()
            eventually("CONNECTED") { repo.connection.value.status == LinkStatus.CONNECTED }
            val s02 = repo.tracks.value.first { it.trackId.value == "S02" }
            repo.authorizeEnqueue(EnqueueRequest(s02.trackId, s02.eventId, IdempotencyKey("ws-key-00000002"), 10, 30_000))
            eventually("acknowledged") { repo.queue.value.singleOrNull()?.jobId != null }
            val jobId = repo.queue.value.single().jobId

            server.dropActiveConnection()
            eventually("link observed down") { repo.connection.value.status != LinkStatus.CONNECTED }
            eventually("reconnected and synced") { repo.connection.value.status == LinkStatus.CONNECTED }
            assertTrue(repo.connection.value.reconnectAttempts >= 1)
            assertTrue(repo.diagnostics.value.snapshotsApplied >= 2, "reconnect reconciles from a snapshot")
            assertEquals(jobId, repo.queue.value.single().jobId, "same server job after reconnect")
            assertEquals(1, server.withSimulator { it.enqueueFramesReceived }, "no enqueue replayed")
            repo.stop()
        }

    @Test fun wrongTokenIsRejectedAndNeverSyncs() =
        runBlocking {
            val repo = repo(bearer = "wrong-token-0123456789")
            delay(2_000)
            assertTrue(repo.connection.value.status != LinkStatus.CONNECTED)
            assertTrue(server.rejectedConnections.get() >= 1)
            assertEquals(0, server.acceptedConnections.get())
            assertNull(repo.session.value.sessionId, "no backend state delivered without auth")
            repo.stop()
        }
}
