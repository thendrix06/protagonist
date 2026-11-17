package capture.sim

import capture.domain.CalibrationStatus
import capture.domain.Clock
import capture.domain.Modality
import capture.domain.PreviewRef
import capture.domain.SectorId
import capture.domain.SensorHealth
import capture.domain.SubjectTrack
import capture.domain.TrackId
import capture.domain.TrackState
import capture.protocol.Envelope
import capture.protocol.Payload
import capture.protocol.ProtocolCodec
import capture.protocol.ProtocolException
import capture.protocol.ProtocolVersion
import capture.protocol.Stream
import capture.protocol.WireCommand
import capture.protocol.WireJob
import capture.protocol.WireJobStatus
import capture.protocol.WireSector
import capture.protocol.WireSensor
import capture.protocol.toDomain
import capture.protocol.toWire
import java.util.PriorityQueue

/**
 * Deterministic simulated capture backend (the development stand-in for the edge service).
 *
 * Speaks capture-protocol 1.0 as text frames. All behaviour is a pure function of the scenario, the injected
 * [Clock], the commands received and the fault-injection calls, so a given test run is exactly repeatable.
 * Camera movement and job scheduling are modelled here (backend responsibility), never in the client.
 */
public class SimulatorServer(
    private val clock: Clock,
    private val scenario: SimScenario = SimScenario.demo(),
) {
    private data class Outgoing(
        val dueAtMs: Long,
        val order: Long,
        val payload: Payload,
    )

    private data class SimJob(
        val jobId: String,
        val idempotencyKey: String,
        val trackId: String,
        val eventId: String,
        var status: WireJobStatus,
        val burstLimit: Int,
        val ttlMs: Long,
        val createdAtMs: Long,
        var updatedAtMs: Long,
        var framesCaptured: Int = 0,
        var reason: String? = null,
        var nextAtMs: Long = 0,
        /** The job is not scheduled before its acknowledgement has been emitted (keeps updates causal). */
        val schedulableAtMs: Long = 0,
    ) {
        val terminal: Boolean
            get() = status in setOf(WireJobStatus.COMPLETED, WireJobStatus.FAILED, WireJobStatus.CANCELLED, WireJobStatus.REJECTED)

        fun toWire(): WireJob =
            WireJob(
                jobId = jobId,
                idempotencyKey = idempotencyKey,
                trackId = trackId,
                eventId = eventId,
                status = status,
                burstLimit = burstLimit,
                ttlMs = ttlMs,
                createdAtMs = createdAtMs,
                updatedAtMs = updatedAtMs,
                cancellable = status == WireJobStatus.QUEUED || status == WireJobStatus.ACQUIRING,
                framesCaptured = if (status == WireJobStatus.QUEUED) null else framesCaptured,
                reason = reason,
            )
    }

    private val startMs = clock.nowMs()
    private var timing = scenario.timing
    private var serverSeq = 0L
    private var outOrder = 0L
    private val outbox = PriorityQueue<Outgoing>(compareBy<Outgoing>({ it.dueAtMs }, { it.order }))

    private var modality = Modality.VISIBLE
    private var sector: SectorId = scenario.initialSector
    private val sensors = Modality.entries.associateWith { SensorHealth.OK to (null as String?) }.toMutableMap()
    private var calibration = scenario.initialCalibration
    private val jobs = LinkedHashMap<String, SimJob>()
    private val jobsByKey = HashMap<String, String>()
    private var nextJobNumber = 1
    private var lastClientSeqProcessed = 0L
    private val lastEmitted = HashMap<String, SubjectTrack>()
    private val forcedStates = HashMap<String, Pair<TrackState, String?>>()
    private val failNextFor = HashSet<String>()

    // -----------------------------------------------------------------------------------------------------------
    // Fault injection / scenario controls

    public fun setSensorHealth(
        modality: Modality,
        health: SensorHealth,
        detail: String? = null,
    ) {
        sensors[modality] = health to detail
        enqueueOut(sensorPayload())
        enqueueOut(sessionPayload())
    }

    public fun setCalibration(status: CalibrationStatus) {
        calibration = status
        enqueueOut(sensorPayload())
    }

    /** Override the scripted state of a track (e.g. to force an occlusion in a test). Null clears the override. */
    public fun forceTrackState(
        trackId: String,
        state: TrackState?,
        reason: String? = null,
    ) {
        if (state == null) forcedStates.remove(trackId) else forcedStates[trackId] = state to reason
    }

    public fun failNextJobFor(trackId: String) {
        failNextFor += trackId
    }

    public fun setAckDelayMs(ms: Long) {
        timing = timing.copy(ackDelayMs = ms)
    }

    public val jobCount: Int get() = jobs.size

    /** Number of `enqueue` frames received, including duplicates. Used to prove no blind replay happens. */
    public var enqueueFramesReceived: Int = 0
        private set

    public fun jobStatuses(): Map<String, String> = jobs.values.associate { it.jobId to it.status.name }

    // -----------------------------------------------------------------------------------------------------------
    // Protocol

    /** Handle one client frame. Responses are queued; collect them with [drain]. */
    public fun receive(text: String) {
        val env =
            try {
                ProtocolCodec.decode(text)
            } catch (_: ProtocolException) {
                return
            }
        if (env.stream != Stream.CONTROL) return
        lastClientSeqProcessed = maxOf(lastClientSeqProcessed, env.seq)
        val now = clock.nowMs()
        when (val p = env.payload) {
            is Payload.SnapshotRequest -> {
                enqueueOut(snapshotPayload(), dueAt = now)
            }

            is Payload.Enqueue -> {
                enqueueFramesReceived++
                handleEnqueue(env.seq, p, now)
            }

            is Payload.Cancel -> {
                handleCancel(env.seq, p, now)
            }

            is Payload.SetMode -> {
                modality = p.modality.toDomain()
                enqueueOut(ack(env.seq, WireCommand.SET_MODE, true))
                enqueueOut(sessionPayload())
            }

            is Payload.SetSector -> {
                val target = scenario.sectors.firstOrNull { it.id.value == p.sectorId }
                if (target == null) {
                    enqueueOut(ack(env.seq, WireCommand.SET_SECTOR, false, reason = "Unknown sector"))
                } else {
                    lastEmitted.values
                        .filter { it.trackId.value in tracksIn(sector) }
                        .forEach { enqueueOut(Payload.TrackRemoved(it.trackId.value, it.eventId.value)) }
                    lastEmitted.keys.removeAll(tracksIn(sector))
                    sector = target.id
                    enqueueOut(ack(env.seq, WireCommand.SET_SECTOR, true))
                    enqueueOut(sessionPayload())
                }
            }

            else -> {}
        }
    }

    /** Advance the simulation to the clock's current time and queue resulting state messages. */
    public fun tick() {
        val now = clock.nowMs()
        updateTracks(now)
        updateJobs(now)
    }

    /** Frames whose due time has passed, in emission order, each with a freshly assigned state seq. */
    public fun drain(): List<String> {
        val now = clock.nowMs()
        val out = ArrayList<String>()
        while (outbox.isNotEmpty() && outbox.peek().dueAtMs <= now) {
            val o = outbox.poll()
            val env = Envelope(ProtocolVersion.Current, ProtocolCodec.typeOf(o.payload), Stream.STATE, ++serverSeq, now, o.payload)
            out += ProtocolCodec.encode(env)
        }
        return out
    }

    public val currentServerSeq: Long get() = serverSeq

    private fun handleEnqueue(
        clientSeq: Long,
        p: Payload.Enqueue,
        now: Long,
    ) {
        val due = now + timing.ackDelayMs
        jobsByKey[p.idempotencyKey]?.let { existing ->
            // Duplicate transmission of the same authorization: same job, no new work.
            enqueueOut(ack(clientSeq, WireCommand.ENQUEUE, true, p.idempotencyKey, existing), due)
            return
        }
        val track = currentTrack(p.trackId, now)
        val policy = scenario.policy
        val rejection =
            when {
                !captureAvailable() -> {
                    "Capture sensor unavailable"
                }

                track == null -> {
                    "Track ${p.trackId} not present in active sector"
                }

                track.eventId.value != p.eventId -> {
                    "Event mismatch"
                }

                track.state == TrackState.LOST || track.state == TrackState.UNCERTAIN -> {
                    "Track ${p.trackId} is ${track.state.name.lowercase()}"
                }

                p.burstLimit !in 1..policy.maxBurstLimit -> {
                    "Burst limit outside 1..${policy.maxBurstLimit}"
                }

                p.ttlMs !in policy.minTtlMs..policy.maxTtlMs -> {
                    "TTL outside ${policy.minTtlMs}..${policy.maxTtlMs} ms"
                }

                jobs.values.any { it.trackId == p.trackId && !it.terminal } -> {
                    "Track ${p.trackId} already has an active job"
                }

                else -> {
                    null
                }
            }
        if (rejection != null) {
            enqueueOut(ack(clientSeq, WireCommand.ENQUEUE, false, p.idempotencyKey, reason = rejection), due)
            return
        }
        val jobId = "J-%04d".format(nextJobNumber++)
        val job =
            SimJob(
                jobId,
                p.idempotencyKey,
                p.trackId,
                p.eventId,
                WireJobStatus.QUEUED,
                p.burstLimit,
                p.ttlMs,
                createdAtMs = now,
                updatedAtMs = now,
                schedulableAtMs = due,
            )
        jobs[jobId] = job
        jobsByKey[p.idempotencyKey] = jobId
        enqueueOut(ack(clientSeq, WireCommand.ENQUEUE, true, p.idempotencyKey, jobId), due)
        enqueueOut(Payload.JobUpdate(job.toWire()), due)
    }

    private fun handleCancel(
        clientSeq: Long,
        p: Payload.Cancel,
        now: Long,
    ) {
        val job = jobs[p.jobId]
        val cancellable = job != null && (job.status == WireJobStatus.QUEUED || job.status == WireJobStatus.ACQUIRING)
        if (job == null || !cancellable || !scenario.policy.operatorCancelAllowed) {
            enqueueOut(ack(clientSeq, WireCommand.CANCEL, false, p.idempotencyKey, p.jobId, "Not cancellable in current state"))
            return
        }
        job.status = WireJobStatus.CANCELLED
        job.reason = "Cancelled by operator"
        job.updatedAtMs = now
        enqueueOut(ack(clientSeq, WireCommand.CANCEL, true, p.idempotencyKey, p.jobId))
        enqueueOut(Payload.JobUpdate(job.toWire()))
    }

    // -----------------------------------------------------------------------------------------------------------
    // Simulation

    private fun tracksIn(sectorId: SectorId): Set<String> = scenario.tracks.filter { it.sectorId == sectorId }.map { it.trackId }.toSet()

    private fun currentTrack(
        trackId: String,
        now: Long,
    ): SubjectTrack? {
        val script = scenario.tracks.firstOrNull { it.trackId == trackId && it.sectorId == sector } ?: return null
        val elapsed = now - startMs
        if (elapsed < script.appearAtMs) return null
        val scripted = script.stateAt(elapsed)
        val (state, reason) = forcedStates[trackId] ?: (scripted.state to scripted.reason)
        val firstSeen = startMs + script.appearAtMs
        val lastSeen =
            when (state) {
                TrackState.ACTIVE, TrackState.UNCERTAIN -> now
                else -> lastEmitted[trackId]?.lastSeenMs ?: now
            }
        val observed =
            if (state == TrackState.ACTIVE || state == TrackState.UNCERTAIN) {
                script.observedIn.filter { sensors[it]?.first == SensorHealth.OK || sensors[it]?.first == SensorHealth.DEGRADED }.toSet()
            } else {
                emptySet()
            }
        return SubjectTrack(
            trackId = TrackId(trackId),
            eventId = capture.domain.EventId(script.eventId),
            state = state,
            box = if (state == TrackState.LOST) null else script.path.boxAt(elapsed),
            firstSeenMs = firstSeen,
            lastSeenMs = lastSeen,
            observedIn = observed,
            stateReason = reason,
            preview = if (state == TrackState.LOST) null else PreviewRef("sim:$trackId", startMs + (elapsed / 2_000) * 2_000),
        )
    }

    private fun updateTracks(now: Long) {
        for (script in scenario.tracks.filter { it.sectorId == sector }) {
            val track = currentTrack(script.trackId, now) ?: continue
            val previous = lastEmitted[script.trackId]
            if (previous == null || previous.copy(lastSeenMs = 0) != track.copy(lastSeenMs = 0)) {
                lastEmitted[script.trackId] = track
                enqueueOut(Payload.TrackUpdate(track.toWire()))
            }
        }
    }

    private fun updateJobs(now: Long) {
        for (job in jobs.values) {
            if (job.terminal) continue
            if (now - job.createdAtMs > job.ttlMs) {
                if (job.status == WireJobStatus.CAPTURING && job.framesCaptured > 0) {
                    finish(job, WireJobStatus.COMPLETED, "TTL reached after ${job.framesCaptured} of ${job.burstLimit} frames", now)
                } else {
                    finish(job, WireJobStatus.FAILED, "TTL expired before capture", now)
                }
            }
        }
        var active = jobs.values.firstOrNull { it.status == WireJobStatus.ACQUIRING || it.status == WireJobStatus.CAPTURING }
        if (active == null) {
            active = jobs.values.firstOrNull { it.status == WireJobStatus.QUEUED && now >= it.schedulableAtMs }
            if (active != null) transition(active, WireJobStatus.ACQUIRING, now, next = now + timing.acquireMs)
        }
        val job = active ?: return
        val track = currentTrack(job.trackId, now)
        when {
            !captureAvailable() -> {
                finish(job, WireJobStatus.FAILED, "Capture sensor invalid", now)
            }

            track == null || track.state == TrackState.LOST -> {
                finish(job, WireJobStatus.FAILED, "Track lost during ${phase(job)}", now)
            }

            track.state == TrackState.UNCERTAIN -> {
                finish(job, WireJobStatus.FAILED, "Track association uncertain; aborted", now)
            }

            job.trackId in failNextFor && job.status == WireJobStatus.CAPTURING -> {
                failNextFor -= job.trackId
                finish(job, WireJobStatus.FAILED, "Simulated capture fault", now)
            }

            job.status == WireJobStatus.ACQUIRING -> {
                if (track.state == TrackState.OCCLUDED) {
                    job.nextAtMs = maxOf(job.nextAtMs, now + timing.tickMs) // wait for visibility
                } else if (now >= job.nextAtMs) {
                    transition(job, WireJobStatus.CAPTURING, now, next = now + timing.frameIntervalMs)
                }
            }

            job.status == WireJobStatus.CAPTURING -> {
                if (track.state != TrackState.ACTIVE) {
                    finish(job, WireJobStatus.FAILED, "Subject not visible during capture (${job.framesCaptured} frames)", now)
                } else if (now >= job.nextAtMs) {
                    job.framesCaptured += 1
                    job.updatedAtMs = now
                    job.nextAtMs = now + timing.frameIntervalMs
                    if (job.framesCaptured >= job.burstLimit) {
                        finish(job, WireJobStatus.COMPLETED, null, now)
                    } else {
                        enqueueOut(Payload.JobUpdate(job.toWire()))
                    }
                }
            }
        }
    }

    private fun phase(job: SimJob) = if (job.status == WireJobStatus.ACQUIRING) "acquisition" else "capture"

    private fun transition(
        job: SimJob,
        status: WireJobStatus,
        now: Long,
        next: Long,
    ) {
        job.status = status
        job.updatedAtMs = now
        job.nextAtMs = next
        enqueueOut(Payload.JobUpdate(job.toWire()))
    }

    private fun finish(
        job: SimJob,
        status: WireJobStatus,
        reason: String?,
        now: Long,
    ) {
        job.status = status
        job.reason = reason
        job.updatedAtMs = now
        enqueueOut(Payload.JobUpdate(job.toWire()))
    }

    private fun captureAvailable(): Boolean = sensors[Modality.VISIBLE]?.first.let { it == SensorHealth.OK || it == SensorHealth.DEGRADED }

    private fun sensorPayload(): Payload.SensorUpdate =
        Payload.SensorUpdate(
            sensors = Modality.entries.map { m -> WireSensor(m.toWire(), sensors.getValue(m).first.toWire(), sensors.getValue(m).second) },
            calibration = calibration.toWire(),
        )

    private fun sessionPayload(): Payload.SessionUpdate =
        Payload.SessionUpdate(
            modality = modality.toWire(),
            captureAvailable = captureAvailable(),
            activeSectorId = sector.value,
            captureUnavailableReason = if (captureAvailable()) null else "Capture sensor invalid",
        )

    private fun snapshotPayload(): Payload.Snapshot {
        val now = clock.nowMs()
        val tracks = tracksIn(sector).mapNotNull { currentTrack(it, now) }
        tracks.forEach { lastEmitted[it.trackId.value] = it }
        return Payload.Snapshot(
            sessionId = scenario.sessionId,
            lastClientSeqProcessed = lastClientSeqProcessed,
            sectors = scenario.sectors.map { WireSector(it.id.value, it.label) },
            modality = modality.toWire(),
            captureAvailable = captureAvailable(),
            policy = scenario.policy.toWire(),
            tracks = tracks.map { it.toWire() },
            jobs = jobs.values.map { it.toWire() },
            sensors = sensorPayload(),
            activeSectorId = sector.value,
            captureUnavailableReason = if (captureAvailable()) null else "Capture sensor invalid",
        )
    }

    private fun ack(
        clientSeq: Long,
        command: WireCommand,
        accepted: Boolean,
        idempotencyKey: String? = null,
        jobId: String? = null,
        reason: String? = null,
    ) = Payload.CommandAck(clientSeq, command, accepted, idempotencyKey, jobId, reason)

    private fun enqueueOut(
        payload: Payload,
        dueAt: Long = clock.nowMs(),
    ) {
        outbox += Outgoing(dueAt, outOrder++, payload)
    }
}
