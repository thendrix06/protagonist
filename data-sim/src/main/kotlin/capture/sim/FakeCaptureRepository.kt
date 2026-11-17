package capture.sim

import capture.domain.CalibrationStatus
import capture.domain.CaptureRepository
import capture.domain.Clock
import capture.domain.EnqueueRules
import capture.domain.Modality
import capture.domain.SensorHealth
import capture.domain.TrackState
import capture.domain.privacy.OperationalLog
import capture.remote.ProtocolCaptureClient
import kotlinx.coroutines.CoroutineScope

/** Operator-visible simulator controls (Settings > Simulator) and test fault injection. */
public interface SimulatorControls {
    public fun dropLink()

    public fun restoreLink()

    public fun dropLinkAfterNextCommand()

    public fun injectSeqGap()

    public fun duplicateNextServerFrame()

    public fun setSensorHealth(
        modality: Modality,
        health: SensorHealth,
        detail: String? = null,
    )

    public fun setCalibration(status: CalibrationStatus)

    public fun forceTrackState(
        trackId: String,
        state: TrackState?,
        reason: String? = null,
    )

    public fun failNextJobFor(trackId: String)

    public fun setAckDelayMs(ms: Long)
}

/**
 * Deterministic [CaptureRepository] backed by [SimulatorServer] through a loopback channel.
 *
 * It deliberately reuses [ProtocolCaptureClient], so the simulator exercises the same acknowledgement, seq-gap and
 * reconnect-reconciliation code paths as the production remote adapter.
 */
public class FakeCaptureRepository private constructor(
    private val client: ProtocolCaptureClient,
    public val server: SimulatorServer,
    private val channel: LoopbackControlChannel,
) : CaptureRepository by client,
    SimulatorControls {
    public fun start(): Unit = client.start()

    /** Stops the client and the simulation ticker. */
    public fun stop() {
        client.stop()
        channel.stopTicker()
    }

    override fun dropLink(): Unit = channel.dropLink()

    override fun restoreLink(): Unit = channel.restoreLink()

    override fun dropLinkAfterNextCommand(): Unit = channel.dropLinkAfterNextCommand()

    override fun injectSeqGap(): Unit = channel.dropNextServerFrames(1)

    override fun duplicateNextServerFrame(): Unit = channel.duplicateNextServerFrame()

    override fun setSensorHealth(
        modality: Modality,
        health: SensorHealth,
        detail: String?,
    ): Unit = server.setSensorHealth(modality, health, detail)

    override fun setCalibration(status: CalibrationStatus): Unit = server.setCalibration(status)

    override fun forceTrackState(
        trackId: String,
        state: TrackState?,
        reason: String?,
    ): Unit = server.forceTrackState(trackId, state, reason)

    override fun failNextJobFor(trackId: String): Unit = server.failNextJobFor(trackId)

    override fun setAckDelayMs(ms: Long): Unit = server.setAckDelayMs(ms)

    public companion object {
        public fun create(
            scope: CoroutineScope,
            clock: Clock,
            scenario: SimScenario = SimScenario.demo(),
            rules: () -> EnqueueRules = { EnqueueRules() },
            clientConfig: ProtocolCaptureClient.Config = ProtocolCaptureClient.Config(),
            log: OperationalLog? = null,
        ): FakeCaptureRepository {
            val server = SimulatorServer(clock, scenario)
            val channel = LoopbackControlChannel(server, scope, scenario.timing.tickMs)
            val client = ProtocolCaptureClient(channel, scope, clock, simulated = true, rules = rules, config = clientConfig, log = log)
            return FakeCaptureRepository(client, server, channel)
        }
    }
}
