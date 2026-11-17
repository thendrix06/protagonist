package capture.remote

import capture.domain.CaptureRepository
import capture.domain.Clock
import capture.domain.CommandResult
import capture.domain.ConnectionState
import capture.domain.EnqueueEligibility
import capture.domain.EnqueuePolicy
import capture.domain.EnqueueRequest
import capture.domain.EnqueueRules
import capture.domain.EventId
import capture.domain.IdempotencyKey
import capture.domain.JobId
import capture.domain.JobStatus
import capture.domain.LinkDiagnostics
import capture.domain.LinkStatus
import capture.domain.Modality
import capture.domain.QueueEntry
import capture.domain.SectorId
import capture.domain.SensorState
import capture.domain.SessionState
import capture.domain.SubjectTrack
import capture.domain.TrackId
import capture.domain.privacy.OperationalLog
import capture.domain.privacy.OperationalLog.Field
import capture.domain.privacy.OperationalLog.Kind
import capture.protocol.Envelope
import capture.protocol.Payload
import capture.protocol.ProtocolCodec
import capture.protocol.ProtocolException
import capture.protocol.ProtocolVersion
import capture.protocol.Stream
import capture.protocol.WireCommand
import capture.protocol.WireJob
import capture.protocol.idempotencyKeyOrNull
import capture.protocol.toDomain
import capture.protocol.toWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Transport-agnostic [CaptureRepository] that speaks capture-protocol 1.x over a [ControlChannel].
 *
 * Used by both the simulator (loopback channel) and the production `RemoteCaptureRepository` (WSS channel), so the
 * sequencing and reconciliation rules below are exercised identically in simulation and in the field:
 *
 *  - Every server `state` message must arrive with seq = last + 1. Duplicates are dropped. A gap discards deltas,
 *    sends `snapshot_request`, and blocks commands (SYNCING) until a snapshot is applied.
 *  - An enqueue is shown as PENDING_ACK until a `command_ack`/`job_update` arrives. If the link drops first it
 *    becomes UNCONFIRMED. It is NEVER retransmitted automatically. After reconnect the snapshot decides: the job is
 *    present (matched by idempotency key) or it is marked NOT_RECEIVED for the operator to re-authorize explicitly.
 */
public class ProtocolCaptureClient(
    private val channel: ControlChannel,
    private val scope: CoroutineScope,
    private val clock: Clock,
    simulated: Boolean,
    private val rules: () -> EnqueueRules = { EnqueueRules() },
    private val config: Config = Config(),
    private val log: OperationalLog? = null,
) : CaptureRepository {
    public data class Config(
        /** How long an enqueue may stay PENDING_ACK before it is marked UNCONFIRMED and a snapshot is requested. */
        val ackTimeoutMs: Long = 5_000,
        /** Maximum number of terminal queue rows kept for operator context. */
        val terminalHistory: Int = 50,
    )

    private data class PendingMeta(
        val clientSeq: Long,
        val epoch: Int,
    )

    private val _session = MutableStateFlow(SessionState.Initial)
    override val session: StateFlow<SessionState> = _session.asStateFlow()
    private val _tracks = MutableStateFlow<List<SubjectTrack>>(emptyList())
    override val tracks: StateFlow<List<SubjectTrack>> = _tracks.asStateFlow()
    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    override val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()
    private val _sensors = MutableStateFlow(SensorState.Unknown)
    override val sensors: StateFlow<SensorState> = _sensors.asStateFlow()
    private val _connection = MutableStateFlow(ConnectionState.initial(simulated))
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    private val _diagnostics = MutableStateFlow(LinkDiagnostics())
    override val diagnostics: StateFlow<LinkDiagnostics> = _diagnostics.asStateFlow()
    private val _selected = MutableStateFlow<TrackId?>(null)
    override val selectedTrackId: StateFlow<TrackId?> = _selected.asStateFlow()

    /** Guards all mutable protocol state below. */
    private val stateLock = Mutex()

    /** Serializes seq allocation + transmission so control frames leave in seq order. Always taken before [stateLock]. */
    private val sendLock = Mutex()

    private val trackMap = LinkedHashMap<TrackId, SubjectTrack>()
    private val entries = LinkedHashMap<IdempotencyKey, QueueEntry>()
    private val pendingMeta = HashMap<IdempotencyKey, PendingMeta>()
    private var lastServerSeq = 0L
    private var awaitingSnapshot = true
    private var snapshotRequestOutstanding = false
    private var clientSeq = 0L
    private var epoch = 0
    private var channelState = ChannelState.CLOSED
    private var protocolMismatch: String? = null
    private var collectors: List<Job> = emptyList()

    /** Starts collecting from the channel and opens it. Idempotent. */
    public fun start() {
        if (collectors.isNotEmpty()) return
        collectors =
            listOf(
                scope.launch(start = CoroutineStart.UNDISPATCHED) { channel.state.collect { onChannelState(it) } },
                scope.launch(start = CoroutineStart.UNDISPATCHED) { channel.incoming.collect { onFrame(it) } },
            )
        channel.connect()
    }

    public fun stop() {
        channel.disconnect()
        collectors.forEach { it.cancel() }
        collectors = emptyList()
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Local selection: never transmitted, never authorizes anything.

    override fun selectTrack(trackId: TrackId?) {
        if (trackId != null && _tracks.value.none { it.trackId == trackId }) return
        _selected.value = trackId
        log?.record(Kind.SELECT, Field.TRACK to trackId)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Commands

    override suspend fun authorizeEnqueue(request: EnqueueRequest): CommandResult {
        var refused: CommandResult.Refused? = null
        var frame: String? = null
        var meta: PendingMeta? = null
        sendLock.withLock {
            stateLock.withLock {
                val track = trackMap[request.trackId]
                val eligibility =
                    EnqueuePolicy.evaluate(track, _connection.value, _session.value, entries.values.toList(), rules())
                refused =
                    when {
                        eligibility is EnqueueEligibility.Blocked -> {
                            CommandResult.Refused(eligibility.detail)
                        }

                        entries.containsKey(request.idempotencyKey) -> {
                            CommandResult.Refused("Duplicate idempotency key")
                        }

                        track != null && track.eventId != request.eventId -> {
                            CommandResult.Refused("Track event changed; reselect")
                        }

                        request.burstLimit > _session.value.policy.maxBurstLimit -> {
                            CommandResult.Refused("Burst limit exceeds backend maximum ${_session.value.policy.maxBurstLimit}")
                        }

                        else -> {
                            null
                        }
                    }
                if (refused != null) return@withLock
                val seq = ++clientSeq
                val now = clock.nowMs()
                val pending = PendingMeta(seq, epoch)
                meta = pending
                pendingMeta[request.idempotencyKey] = pending
                entries[request.idempotencyKey] =
                    QueueEntry(
                        idempotencyKey = request.idempotencyKey,
                        trackId = request.trackId,
                        eventId = request.eventId,
                        jobId = null,
                        status = JobStatus.PENDING_ACK,
                        burstLimit = request.burstLimit,
                        ttlMs = request.ttlMs,
                        requestedAtMs = now,
                        updatedAtMs = now,
                    )
                publishQueue()
                frame =
                    encode(
                        Stream.CONTROL,
                        seq,
                        Payload.Enqueue(
                            request.idempotencyKey.value,
                            request.trackId.value,
                            request.eventId.value,
                            request.burstLimit,
                            request.ttlMs,
                        ),
                    )
            }
            refused?.let {
                log?.record(Kind.ENQUEUE_REFUSED, Field.TRACK to request.trackId)
                return it
            }
            val transmitted = channel.send(frame!!)
            stateLock.withLock {
                if (transmitted) {
                    _diagnostics.update { it.copy(commandsSent = it.commandsSent + 1) }
                } else {
                    // Never handed to the transport, so the backend cannot have it. Terminal; not retried.
                    updateEntry(request.idempotencyKey) {
                        it.copy(status = JobStatus.NOT_RECEIVED, reason = "Not transmitted: link down", updatedAtMs = clock.nowMs())
                    }
                    pendingMeta.remove(request.idempotencyKey)
                }
            }
            if (!transmitted) return CommandResult.Refused("Not transmitted: link down")
        }
        log?.record(
            Kind.ENQUEUE_SENT,
            Field.TRACK to request.trackId,
            Field.IDEMPOTENCY to request.idempotencyKey,
            Field.SEQ to meta?.clientSeq,
        )
        scheduleAckTimeout(request.idempotencyKey, meta!!)
        return CommandResult.Sent
    }

    override suspend fun cancelJob(jobId: JobId): CommandResult {
        val entry = stateLock.withLock { entries.values.firstOrNull { it.jobId == jobId } }
        return when {
            entry == null -> CommandResult.Refused("Unknown job $jobId")
            !_session.value.policy.operatorCancelAllowed -> CommandResult.Refused("Backend policy does not allow operator cancel")
            !entry.cancellable -> CommandResult.Refused("Backend reports job $jobId is not cancellable")
            else -> sendCommand(Payload.Cancel(jobId.value, entry.idempotencyKey.value))
        }
    }

    override suspend fun setObservationMode(modality: Modality): CommandResult {
        log?.record(Kind.MODE, Field.MODALITY to modality.name)
        return sendCommand(Payload.SetMode(modality.toWire()))
    }

    override suspend fun selectSector(sectorId: SectorId): CommandResult {
        log?.record(Kind.SECTOR, Field.SECTOR to sectorId)
        return sendCommand(Payload.SetSector(sectorId.value))
    }

    private suspend fun sendCommand(
        payload: Payload,
        requireConnected: Boolean = true,
    ): CommandResult =
        sendLock.withLock {
            val frame =
                stateLock.withLock {
                    if (requireConnected && !_connection.value.canCommand) return CommandResult.Refused(linkRefusal())
                    if (!requireConnected && channelState != ChannelState.OPEN) return CommandResult.Refused("Link down")
                    encode(Stream.CONTROL, ++clientSeq, payload)
                }
            if (channel.send(frame)) {
                _diagnostics.update { it.copy(commandsSent = it.commandsSent + 1) }
                CommandResult.Sent
            } else {
                CommandResult.Refused("Not transmitted: link down")
            }
        }

    private fun linkRefusal(): String =
        when (_connection.value.status) {
            LinkStatus.SYNCING -> "Reconciling with backend"
            else -> "Link down"
        }

    private suspend fun requestSnapshot(reason: String) {
        val shouldSend =
            stateLock.withLock {
                if (snapshotRequestOutstanding || channelState != ChannelState.OPEN || protocolMismatch != null) {
                    false
                } else {
                    snapshotRequestOutstanding = true
                    true
                }
            }
        if (!shouldSend) return
        if (sendCommand(Payload.SnapshotRequest(reason), requireConnected = false) !is CommandResult.Sent) {
            stateLock.withLock { snapshotRequestOutstanding = false }
        }
    }

    private fun scheduleAckTimeout(
        key: IdempotencyKey,
        meta: PendingMeta,
    ) {
        scope.launch {
            delay(config.ackTimeoutMs)
            val needSnapshot =
                stateLock.withLock {
                    val entry = entries[key]
                    if (entry?.status == JobStatus.PENDING_ACK && pendingMeta[key] == meta) {
                        updateEntry(key) {
                            it.copy(
                                status = JobStatus.UNCONFIRMED,
                                reason = "No acknowledgement within ${config.ackTimeoutMs / 1000}s",
                                updatedAtMs = clock.nowMs(),
                            )
                        }
                        true
                    } else {
                        false
                    }
                }
            if (needSnapshot) requestSnapshot("ack_timeout")
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Transport events

    private suspend fun onChannelState(state: ChannelState) {
        val requestSync =
            stateLock.withLock {
                val previous = channelState
                channelState = state
                when (state) {
                    ChannelState.OPEN -> {
                        if (previous != ChannelState.OPEN) {
                            epoch++
                            awaitingSnapshot = true
                            snapshotRequestOutstanding = false
                        }
                        publishConnection(LinkStatus.SYNCING)
                        previous != ChannelState.OPEN
                    }

                    ChannelState.CONNECTING, ChannelState.CLOSED -> {
                        awaitingSnapshot = true
                        snapshotRequestOutstanding = false
                        markInFlightUnconfirmed()
                        publishConnection(if (state == ChannelState.CONNECTING) LinkStatus.CONNECTING else LinkStatus.OFFLINE)
                        false
                    }
                }
            }
        log?.record(Kind.LINK, Field.STATUS to state.name)
        if (requestSync) requestSnapshot("connect")
    }

    private fun markInFlightUnconfirmed() {
        val now = clock.nowMs()
        entries.values
            .filter { it.status == JobStatus.PENDING_ACK }
            .forEach { e ->
                entries[e.idempotencyKey] =
                    e.copy(status = JobStatus.UNCONFIRMED, reason = "Link lost before acknowledgement", updatedAtMs = now)
            }
        publishQueue()
    }

    private suspend fun onFrame(text: String) {
        val envelope =
            try {
                ProtocolCodec.decode(text)
            } catch (e: ProtocolException.UnsupportedVersion) {
                stateLock.withLock {
                    protocolMismatch = "Backend protocol ${e.version} unsupported (client ${ProtocolVersion.Current})"
                    _diagnostics.update {
                        it.copy(unsupportedVersionErrors = it.unsupportedVersionErrors + 1, lastError = protocolMismatch)
                    }
                    awaitingSnapshot = true
                    publishConnection(LinkStatus.SYNCING)
                }
                return
            } catch (e: ProtocolException) {
                stateLock.withLock {
                    _diagnostics.update { it.copy(decodeErrors = it.decodeErrors + 1, lastError = e.message) }
                    awaitingSnapshot = true
                    publishConnection(LinkStatus.SYNCING)
                }
                // We cannot know which state change was lost, so resynchronize.
                requestSnapshot("decode_error")
                return
            }
        val needSnapshot = stateLock.withLock { handleEnvelope(envelope) }
        if (needSnapshot) requestSnapshot("seq_gap")
    }

    /** Returns true if a snapshot must be requested. Caller holds [stateLock]. */
    private fun handleEnvelope(env: Envelope): Boolean {
        val now = clock.nowMs()
        if (env.stream == Stream.HEARTBEAT) {
            _connection.update { it.copy(lastMessageAtMs = now) }
            return false
        }
        if (env.stream != Stream.STATE) return false
        _diagnostics.update { it.copy(messagesReceived = it.messagesReceived + 1, lastApparentLatencyMs = now - env.timestampMs) }

        val payload = env.payload
        if (payload is Payload.Snapshot) {
            if (!awaitingSnapshot && env.seq <= lastServerSeq) {
                _diagnostics.update { it.copy(duplicatesDropped = it.duplicatesDropped + 1) }
                return false
            }
            applySnapshot(payload, env)
            return false
        }
        if (awaitingSnapshot) return false
        if (env.seq <= lastServerSeq) {
            _diagnostics.update { it.copy(duplicatesDropped = it.duplicatesDropped + 1) }
            return false
        }
        if (env.seq > lastServerSeq + 1) {
            _diagnostics.update { it.copy(seqGapsDetected = it.seqGapsDetected + 1) }
            log?.record(Kind.SEQ_GAP, Field.SEQ to env.seq, Field.COUNT to (env.seq - lastServerSeq - 1))
            awaitingSnapshot = true
            publishConnection(LinkStatus.SYNCING)
            return true
        }
        lastServerSeq = env.seq
        applyDelta(payload, env)
        publishConnection(LinkStatus.CONNECTED)
        return false
    }

    private fun applyDelta(
        payload: Payload,
        env: Envelope,
    ) {
        when (payload) {
            is Payload.TrackUpdate -> {
                val track = payload.track.toDomain()
                trackMap[track.trackId] = track
                publishTracks()
            }

            is Payload.TrackRemoved -> {
                trackMap.remove(TrackId(payload.trackId))
                publishTracks()
            }

            is Payload.JobUpdate -> {
                upsertJob(payload.job)
                publishQueue()
            }

            is Payload.SensorUpdate -> {
                _sensors.value = payload.toDomain(env.timestampMs)
                log?.record(Kind.SENSOR, Field.COUNT to payload.sensors.size)
            }

            is Payload.SessionUpdate -> {
                _session.update {
                    it.copy(
                        modality = payload.modality.toDomain(),
                        activeSector = payload.activeSectorId?.let(::SectorId) ?: it.activeSector,
                        captureAvailable = payload.captureAvailable,
                        captureUnavailableReason = payload.captureUnavailableReason,
                    )
                }
            }

            is Payload.CommandAck -> {
                handleAck(payload)
            }

            is Payload.Unknown -> {
                _diagnostics.update { it.copy(unknownMessageTypes = it.unknownMessageTypes + 1) }
            }

            is Payload.Heartbeat, is Payload.Snapshot, is Payload.Enqueue, is Payload.Cancel, is Payload.SetMode,
            is Payload.SetSector, is Payload.SnapshotRequest,
            -> {}
        }
    }

    private fun handleAck(ack: Payload.CommandAck) {
        val key = ack.idempotencyKey?.let { runCatching { IdempotencyKey(it) }.getOrNull() }
        when (ack.command) {
            WireCommand.ENQUEUE -> {
                if (key == null) return
                val entry = entries[key] ?: return
                pendingMeta.remove(key)
                val updated =
                    when {
                        !ack.accepted -> {
                            entry.copy(
                                status = JobStatus.REJECTED,
                                reason = ack.reason ?: "Rejected by backend",
                                updatedAtMs = clock.nowMs(),
                            )
                        }

                        entry.status.clientOnly -> {
                            entry.copy(
                                status = JobStatus.QUEUED,
                                jobId = ack.jobId?.let(::JobId) ?: entry.jobId,
                                reason = null,
                                updatedAtMs = clock.nowMs(),
                            )
                        }

                        else -> {
                            entry.copy(jobId = entry.jobId ?: ack.jobId?.let(::JobId))
                        }
                    }
                entries[key] = updated
                log?.record(Kind.ACK, Field.IDEMPOTENCY to key, Field.JOB to ack.jobId, Field.STATUS to updated.status.name)
                publishQueue()
            }

            WireCommand.CANCEL -> {
                if (!ack.accepted && key != null) {
                    updateEntry(key) { it.copy(reason = "Cancel refused: ${ack.reason ?: "backend policy"}") }
                }
            }

            WireCommand.SET_MODE, WireCommand.SET_SECTOR, WireCommand.SNAPSHOT_REQUEST -> {
                if (!ack.accepted) _diagnostics.update { it.copy(lastError = "${ack.command} refused: ${ack.reason}") }
            }
        }
    }

    private fun upsertJob(job: WireJob) {
        val key = job.idempotencyKeyOrNull() ?: return
        val existing = entries[key]
        entries[key] =
            QueueEntry(
                idempotencyKey = key,
                trackId = TrackId(job.trackId),
                eventId = EventId(job.eventId),
                jobId = JobId(job.jobId),
                status = job.status.toDomain(),
                burstLimit = job.burstLimit,
                ttlMs = job.ttlMs,
                requestedAtMs = existing?.requestedAtMs ?: job.createdAtMs,
                updatedAtMs = job.updatedAtMs,
                framesCaptured = job.framesCaptured,
                reason = job.reason,
                cancellable = job.cancellable,
            )
        pendingMeta.remove(key)
        if (existing == null || existing.status != entries.getValue(key).status) {
            log?.record(Kind.JOB_STATUS, Field.JOB to job.jobId, Field.STATUS to job.status.name)
        }
    }

    private fun applySnapshot(
        snapshot: Payload.Snapshot,
        env: Envelope,
    ) {
        _session.value =
            SessionState(
                sessionId = snapshot.sessionId,
                sectors = snapshot.sectors.map { it.toDomain() },
                activeSector = snapshot.activeSectorId?.let(::SectorId),
                modality = snapshot.modality.toDomain(),
                captureAvailable = snapshot.captureAvailable,
                captureUnavailableReason = snapshot.captureUnavailableReason,
                policy = snapshot.policy.toDomain(),
            )
        trackMap.clear()
        snapshot.tracks
            .map { it.toDomain() }
            .sortedWith(compareBy({ it.firstSeenMs }, { it.trackId.value }))
            .forEach { trackMap[it.trackId] = it }
        publishTracks()
        _sensors.value = snapshot.sensors.toDomain(env.timestampMs)

        val snapshotKeys = HashSet<IdempotencyKey>()
        snapshot.jobs.sortedBy { it.createdAtMs }.forEach { job ->
            job.idempotencyKeyOrNull()?.let { snapshotKeys += it }
            upsertJob(job)
        }
        val now = clock.nowMs()
        for (entry in entries.values.toList()) {
            if (entry.idempotencyKey in snapshotKeys) continue
            when {
                entry.status == JobStatus.PENDING_ACK || entry.status == JobStatus.UNCONFIRMED -> {
                    val meta = pendingMeta[entry.idempotencyKey]
                    val serverHasSeenIt = meta == null || meta.epoch < epoch || meta.clientSeq <= snapshot.lastClientSeqProcessed
                    if (serverHasSeenIt) {
                        entries[entry.idempotencyKey] =
                            entry.copy(
                                status = JobStatus.NOT_RECEIVED,
                                reason = "Backend has no record; not resent. Authorize again if still needed.",
                                updatedAtMs = now,
                            )
                        pendingMeta.remove(entry.idempotencyKey)
                    }
                }

                entry.status.terminal -> {}

                // keep local history
                else -> {
                    entries.remove(entry.idempotencyKey)
                } // backend no longer reports this job
            }
        }
        trimHistory()
        publishQueue()

        lastServerSeq = env.seq
        awaitingSnapshot = false
        snapshotRequestOutstanding = false
        _diagnostics.update { it.copy(snapshotsApplied = it.snapshotsApplied + 1) }
        _connection.update { it.copy(lastSnapshotAtMs = now) }
        log?.record(Kind.SNAPSHOT, Field.SEQ to env.seq, Field.COUNT to snapshot.jobs.size)
        publishConnection(if (channelState == ChannelState.OPEN && protocolMismatch == null) LinkStatus.CONNECTED else LinkStatus.SYNCING)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Publishing helpers (caller holds stateLock)

    private fun updateEntry(
        key: IdempotencyKey,
        transform: (QueueEntry) -> QueueEntry,
    ) {
        val e = entries[key] ?: return
        entries[key] = transform(e)
        publishQueue()
    }

    private fun trimHistory() {
        val terminal = entries.values.filter { it.status.terminal }
        val excess = terminal.size - config.terminalHistory
        if (excess > 0) terminal.take(excess).forEach { entries.remove(it.idempotencyKey) }
    }

    private fun publishQueue() {
        _queue.value = entries.values.toList()
    }

    private fun publishTracks() {
        _tracks.value = trackMap.values.toList()
        val selected = _selected.value
        if (selected != null && selected !in trackMap) {
            // The backend removed the track. Never silently move the highlight to another subject.
            _selected.value = null
        }
    }

    private fun publishConnection(status: LinkStatus) {
        val effective =
            when {
                channelState != ChannelState.OPEN -> {
                    if (channelState ==
                        ChannelState.CONNECTING
                    ) {
                        LinkStatus.CONNECTING
                    } else {
                        LinkStatus.OFFLINE
                    }
                }

                awaitingSnapshot || protocolMismatch != null -> {
                    LinkStatus.SYNCING
                }

                else -> {
                    status
                }
            }
        _connection.update {
            it.copy(
                status = effective,
                lastServerSeq = lastServerSeq,
                lastMessageAtMs = if (effective == LinkStatus.CONNECTED) clock.nowMs() else it.lastMessageAtMs,
                reconnectAttempts = (channel.connectAttempts.value - 1).coerceAtLeast(0),
                detail = protocolMismatch,
            )
        }
    }

    private fun encode(
        stream: Stream,
        seq: Long,
        payload: Payload,
    ): String = ProtocolCodec.encode(Envelope(ProtocolVersion.Current, ProtocolCodec.typeOf(payload), stream, seq, clock.nowMs(), payload))
}
