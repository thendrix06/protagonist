package capture.remote

import capture.domain.CaptureRepository
import capture.domain.Clock
import capture.domain.EnqueueRules
import capture.domain.privacy.OperationalLog
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/**
 * Production [CaptureRepository]: capture-protocol over an authenticated WSS control channel.
 *
 * Live video is NOT carried here; pair this with a `VideoSource` implementation on its own channel.
 * Compose screens are unchanged between the simulator and this adapter.
 */
public class RemoteCaptureRepository private constructor(
    private val client: ProtocolCaptureClient,
) : CaptureRepository by client {
    public fun start(): Unit = client.start()

    public fun stop(): Unit = client.stop()

    public companion object {
        public fun create(
            endpoint: RemoteEndpoint,
            credentials: CredentialProvider,
            scope: CoroutineScope,
            clock: Clock,
            rules: () -> EnqueueRules,
            log: OperationalLog? = null,
            okHttp: OkHttpClient = OkHttpClient(),
        ): RemoteCaptureRepository {
            val channel = WebSocketControlChannel(endpoint, credentials, scope, okHttp)
            return RemoteCaptureRepository(ProtocolCaptureClient(channel, scope, clock, simulated = false, rules = rules, log = log))
        }

        /** For integration tests: any [ControlChannel], e.g. [InMemoryControlChannel]. */
        public fun overChannel(
            channel: ControlChannel,
            scope: CoroutineScope,
            clock: Clock,
            rules: () -> EnqueueRules = { EnqueueRules() },
            config: ProtocolCaptureClient.Config = ProtocolCaptureClient.Config(),
        ): RemoteCaptureRepository =
            RemoteCaptureRepository(ProtocolCaptureClient(channel, scope, clock, simulated = false, rules = rules, config = config))
    }
}
