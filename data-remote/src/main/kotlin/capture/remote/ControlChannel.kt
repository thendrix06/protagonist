package capture.remote

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

public enum class ChannelState {
    CLOSED,
    CONNECTING,
    OPEN,
}

/**
 * Reliable, ordered, authenticated control/state channel carrying one JSON envelope per text frame.
 *
 * Live video never travels here (see the `:video` module). Implementations: [InMemoryControlChannel] (tests and
 * the simulator loopback) and [WebSocketControlChannel] (production, WSS + bearer token).
 */
public interface ControlChannel {
    public val state: StateFlow<ChannelState>

    /** Incoming text frames, in order. */
    public val incoming: Flow<String>

    /** Number of times the transport has (re)connected; used for diagnostics only. */
    public val connectAttempts: StateFlow<Int>

    public fun connect()

    public fun disconnect()

    /** Returns false if the frame could not be handed to an open transport. Never retries on its own. */
    public suspend fun send(text: String): Boolean
}

/**
 * Mock transport for integration tests. The test acts as the server: it reads [sent] and calls [deliver].
 */
public class InMemoryControlChannel(
    autoOpen: Boolean = false,
) : ControlChannel {
    private val _state = MutableStateFlow(if (autoOpen) ChannelState.OPEN else ChannelState.CLOSED)
    override val state: StateFlow<ChannelState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.SUSPEND)
    override val incoming: Flow<String> = _incoming.asSharedFlow()

    private val _attempts = MutableStateFlow(0)
    override val connectAttempts: StateFlow<Int> = _attempts.asStateFlow()

    private val _sent = MutableSharedFlow<String>(replay = 4096)

    /** Every frame the client sent while the channel was open, in order. */
    public val sent: SharedFlow<String> = _sent.asSharedFlow()

    public val sentFrames: List<String> get() = _sent.replayCache

    override fun connect() {
        _attempts.value += 1
        _state.value = ChannelState.OPEN
    }

    override fun disconnect() {
        _state.value = ChannelState.CLOSED
    }

    override suspend fun send(text: String): Boolean {
        if (_state.value != ChannelState.OPEN) return false
        _sent.emit(text)
        return true
    }

    /** Simulate a frame arriving from the server. Dropped if the channel is not open. */
    public suspend fun deliver(text: String): Boolean {
        if (_state.value != ChannelState.OPEN) return false
        _incoming.emit(text)
        return true
    }

    /** Force a transport state (e.g. CLOSED to simulate link loss). */
    public fun forceState(state: ChannelState) {
        if (state == ChannelState.OPEN && _state.value != ChannelState.OPEN) _attempts.value += 1
        _state.value = state
    }
}
