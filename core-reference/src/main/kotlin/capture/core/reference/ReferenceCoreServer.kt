package capture.core.reference

import capture.domain.Clock
import capture.sim.SimScenario
import capture.sim.SimulatorServer
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Reference capture-protocol server: the deterministic simulator served over an authenticated WebSocket.
 *
 * Purpose: give Core implementers and the app's production transport (`RemoteCaptureRepository` +
 * `WebSocketControlChannel`) a real network peer that follows `contracts/capture_protocol.json`.
 *
 * - Endpoint: `ws://<host>:<port>/control`, one JSON envelope per text frame.
 * - Auth: `Authorization: Bearer <token>`; anything else is closed with VIOLATED_POLICY before any state is sent.
 * - Plain `ws://` only. For device use put a TLS terminator in front (the app requires `wss://`).
 * - One shared simulated backend; the newest authenticated connection receives state. Frames produced while no
 *   client is connected are lost in transit (the client resynchronizes from a snapshot), like a real backend.
 */
public class ReferenceCoreServer(
    private val token: String,
    private val host: String = "127.0.0.1",
    private val port: Int = 8765,
    private val clock: Clock = Clock { System.currentTimeMillis() },
    scenario: SimScenario = SimScenario.demo(),
) {
    init {
        require(token.length >= 16) { "Use a bearer token of at least 16 characters" }
    }

    /** The simulated backend. Access only through [withSimulator] (it is not thread-safe). */
    private val simulator = SimulatorServer(clock, scenario)
    private val lock = Mutex()
    private val tickMs = scenario.timing.tickMs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var active: DefaultWebSocketServerSession? = null

    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    public val rejectedConnections: AtomicInteger = AtomicInteger()
    public val acceptedConnections: AtomicInteger = AtomicInteger()

    /** Run [block] with exclusive access to the simulator (fault injection, assertions). */
    public suspend fun <T> withSimulator(block: (SimulatorServer) -> T): T = lock.withLock { block(simulator) }

    /** Starts the server and returns the bound port (useful with port 0). */
    public fun start(): Int {
        val server =
            embeddedServer(CIO, host = host, port = port) {
                install(WebSockets) { pingPeriod = 15.seconds }
                routing {
                    webSocket("/control") { serve(this) }
                }
            }
        server.start(wait = false)
        engine = server
        scope.launch {
            while (isActive) {
                delay(tickMs)
                val frames =
                    lock.withLock {
                        simulator.tick()
                        simulator.drain()
                    }
                send(frames)
            }
        }
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    public fun stop() {
        scope.cancel()
        engine?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        engine = null
    }

    /** Server-side link drop (for reconnect tests): closes the active connection without changing backend state. */
    public suspend fun dropActiveConnection() {
        active?.close(CloseReason(CloseReason.Codes.GOING_AWAY, "simulated link loss"))
        active = null
    }

    private suspend fun serve(session: DefaultWebSocketServerSession) {
        val auth = session.call.request.headers["Authorization"]
        if (auth != "Bearer $token") {
            rejectedConnections.incrementAndGet()
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return
        }
        acceptedConnections.incrementAndGet()
        active = session
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val out =
                    lock.withLock {
                        simulator.receive(frame.readText())
                        simulator.drain()
                    }
                send(out)
            }
        } finally {
            if (active === session) active = null
        }
    }

    private suspend fun send(frames: List<String>) {
        val session = active ?: return // lost in transit
        for (f in frames) {
            runCatching { session.send(Frame.Text(f)) }.onFailure { return }
        }
    }
}

/**
 * Command line: `./gradlew :core-reference:run` (port 8765, loopback only).
 * Configure with PROTAGONIST_CORE_TOKEN (required), PROTAGONIST_CORE_HOST and PROTAGONIST_CORE_PORT.
 */
public fun main() {
    val token =
        System.getenv("PROTAGONIST_CORE_TOKEN")
            ?: error("Set PROTAGONIST_CORE_TOKEN (>= 16 chars); the server never runs unauthenticated")
    val host = System.getenv("PROTAGONIST_CORE_HOST") ?: "127.0.0.1"
    val port = System.getenv("PROTAGONIST_CORE_PORT")?.toInt() ?: 8765
    val server = ReferenceCoreServer(token, host, port)
    val bound = server.start()
    println("Reference capture-protocol server on ws://$host:$bound/control (Ctrl+C to stop)")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}
