package capture.sim

import capture.remote.ChannelState
import capture.remote.ControlChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * In-process [ControlChannel] connecting a client to [SimulatorServer], with link fault injection.
 * The server keeps running (and numbering messages) while the link is down, exactly like a real backend.
 */
public class LoopbackControlChannel(
    private val server: SimulatorServer,
    private val scope: CoroutineScope,
    private val tickMs: Long,
) : ControlChannel {
    private val _state = MutableStateFlow(ChannelState.CLOSED)
    override val state: StateFlow<ChannelState> = _state.asStateFlow()
    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 8192)
    override val incoming: Flow<String> = _incoming.asSharedFlow()
    private val _attempts = MutableStateFlow(0)
    override val connectAttempts: StateFlow<Int> = _attempts.asStateFlow()

    private var ticker: Job? = null
    private var dropAfterNextCommand = false
    private var framesToDrop = 0
    private var duplicateNext = false

    override fun connect() {
        ensureTicker()
        if (_state.value != ChannelState.OPEN) {
            _attempts.value += 1
            _state.value = ChannelState.OPEN
        }
    }

    override fun disconnect() {
        _state.value = ChannelState.CLOSED
    }

    override suspend fun send(text: String): Boolean {
        if (_state.value != ChannelState.OPEN) return false
        server.receive(text)
        if (dropAfterNextCommand) {
            // The command reached the backend, but the link fails before any response gets back.
            dropAfterNextCommand = false
            _state.value = ChannelState.CLOSED
        }
        flush()
        return true
    }

    // -- Fault injection ---------------------------------------------------------------------------------------

    public fun dropLink() {
        _state.value = ChannelState.CLOSED
    }

    public fun restoreLink() {
        connect()
    }

    public fun dropLinkAfterNextCommand() {
        dropAfterNextCommand = true
    }

    /** Silently lose the next [count] server frames while the link stays up (creates a seq gap). */
    public fun dropNextServerFrames(count: Int = 1) {
        framesToDrop += count
    }

    public fun duplicateNextServerFrame() {
        duplicateNext = true
    }

    // ----------------------------------------------------------------------------------------------------------

    private fun ensureTicker() {
        if (ticker != null) return
        ticker =
            scope.launch {
                while (isActive) {
                    delay(tickMs)
                    server.tick()
                    flush()
                }
            }
    }

    public fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun flush() {
        val frames = server.drain()
        if (_state.value != ChannelState.OPEN) return // lost in transit
        for (frame in frames) {
            if (framesToDrop > 0) {
                framesToDrop--
                continue
            }
            _incoming.tryEmit(frame)
            if (duplicateNext) {
                duplicateNext = false
                _incoming.tryEmit(frame)
            }
        }
    }
}
