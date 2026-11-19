package capture.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Supplies a short-lived bearer token for the control channel. Never logged. */
public fun interface CredentialProvider {
    public suspend fun bearerToken(): String?
}

public data class RemoteEndpoint(
    /** Must be `wss://` unless [allowInsecureForLoopbackTests] is set. */
    val controlUrl: String,
    val allowInsecureForLoopbackTests: Boolean = false,
    val initialBackoffMs: Long = 500,
    val maxBackoffMs: Long = 15_000,
) {
    init {
        val scheme = controlUrl.substringBefore("://").lowercase()
        require(scheme == "wss" || (allowInsecureForLoopbackTests && scheme == "ws")) {
            "Control channel must use wss:// (got $scheme)"
        }
    }
}

/**
 * Production control channel: authenticated WebSocket over TLS (OkHttp), automatic reconnect with exponential
 * backoff. It reconnects the transport only; it never replays application frames. Reconciliation after reconnect is
 * done by [ProtocolCaptureClient] via a snapshot.
 */
public class WebSocketControlChannel(
    private val endpoint: RemoteEndpoint,
    private val credentials: CredentialProvider,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = OkHttpClient(),
) : ControlChannel {
    private val _state = MutableStateFlow(ChannelState.CLOSED)
    override val state: StateFlow<ChannelState> = _state.asStateFlow()
    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.SUSPEND)
    override val incoming: Flow<String> = _incoming.asSharedFlow()
    private val _attempts = MutableStateFlow(0)
    override val connectAttempts: StateFlow<Int> = _attempts.asStateFlow()

    @Volatile private var socket: WebSocket? = null

    @Volatile private var wanted = false
    private var loop: Job? = null

    override fun connect() {
        if (wanted) return
        wanted = true
        loop = scope.launch { openOnce() }
    }

    override fun disconnect() {
        wanted = false
        loop?.cancel()
        socket?.close(NORMAL_CLOSURE, "client disconnect")
        socket = null
        _state.value = ChannelState.CLOSED
    }

    override suspend fun send(text: String): Boolean {
        val ws = socket ?: return false
        if (_state.value != ChannelState.OPEN) return false
        return ws.send(text)
    }

    private suspend fun openOnce() {
        _state.value = ChannelState.CONNECTING
        _attempts.value += 1
        val token = credentials.bearerToken()
        val request =
            Request
                .Builder()
                .url(endpoint.controlUrl.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").toHttpUrl())
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
        socket = client.newWebSocket(request, Listener())
    }

    private fun scheduleReconnect() {
        if (!wanted) return
        val attempt = _attempts.value
        val backoff = (endpoint.initialBackoffMs shl (attempt - 1).coerceIn(0, 10)).coerceAtMost(endpoint.maxBackoffMs)
        loop =
            scope.launch {
                delay(backoff)
                if (wanted) openOnce()
            }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            if (webSocket === socket) _state.value = ChannelState.OPEN
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            if (webSocket !== socket) return
            if (!_incoming.tryEmit(text)) {
                // Consumer overwhelmed: drop the link so the client resynchronizes from a snapshot.
                webSocket.cancel()
            }
        }

        /**
         * The server initiated the close. OkHttp waits for us to complete the handshake; until then the socket is
         * half-closed and would never reach onClosed, so the link would look up while no state can arrive.
         */
        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            webSocket.close(NORMAL_CLOSURE, null)
            onDown(webSocket)
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) = onDown(webSocket)

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) = onDown(webSocket)

        private fun onDown(webSocket: WebSocket) {
            if (webSocket !== socket) return
            socket = null
            _state.value = ChannelState.CLOSED
            scheduleReconnect()
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
