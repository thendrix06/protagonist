package capture.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

public data class ProtocolVersion(
    val major: Int,
    val minor: Int,
) {
    override fun toString(): String = "$major.$minor"

    public companion object {
        public val Current: ProtocolVersion = ProtocolVersion(1, 0)
        public const val SUPPORTED_MAJOR: Int = 1

        public fun parse(value: String): ProtocolVersion {
            val parts = value.split('.')
            val major = parts.getOrNull(0)?.toIntOrNull()
            val minor = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size != 2 || major == null || minor == null || major < 0 || minor < 0) {
                throw ProtocolException.Malformed("Bad protocolVersion '$value'")
            }
            return ProtocolVersion(major, minor)
        }
    }
}

public sealed class ProtocolException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Critical: the peer speaks a major version this client does not support. */
    public class UnsupportedVersion(
        public val version: String,
    ) : ProtocolException("Unsupported protocol major version $version")

    public class Malformed(
        message: String,
        cause: Throwable? = null,
    ) : ProtocolException(message, cause)
}

/**
 * Encodes and decodes [Envelope]s per contracts/capture_protocol.json.
 *
 * Compatibility policy: reject an unknown MAJOR version and any missing required field; accept a higher MINOR,
 * ignore unknown optional fields, and surface unknown message types as [Payload.Unknown].
 */
public object ProtocolCodec {
    public val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }

    private val registry: Map<String, KSerializer<out Payload>> =
        mapOf(
            "snapshot" to Payload.Snapshot.serializer(),
            "track_update" to Payload.TrackUpdate.serializer(),
            "track_removed" to Payload.TrackRemoved.serializer(),
            "job_update" to Payload.JobUpdate.serializer(),
            "sensor_update" to Payload.SensorUpdate.serializer(),
            "session_update" to Payload.SessionUpdate.serializer(),
            "command_ack" to Payload.CommandAck.serializer(),
            "heartbeat" to Payload.Heartbeat.serializer(),
            "enqueue" to Payload.Enqueue.serializer(),
            "cancel" to Payload.Cancel.serializer(),
            "set_mode" to Payload.SetMode.serializer(),
            "set_sector" to Payload.SetSector.serializer(),
            "snapshot_request" to Payload.SnapshotRequest.serializer(),
        )

    public val knownTypes: Set<String> get() = registry.keys

    public fun typeOf(payload: Payload): String =
        when (payload) {
            is Payload.Snapshot -> "snapshot"
            is Payload.TrackUpdate -> "track_update"
            is Payload.TrackRemoved -> "track_removed"
            is Payload.JobUpdate -> "job_update"
            is Payload.SensorUpdate -> "sensor_update"
            is Payload.SessionUpdate -> "session_update"
            is Payload.CommandAck -> "command_ack"
            is Payload.Heartbeat -> "heartbeat"
            is Payload.Enqueue -> "enqueue"
            is Payload.Cancel -> "cancel"
            is Payload.SetMode -> "set_mode"
            is Payload.SetSector -> "set_sector"
            is Payload.SnapshotRequest -> "snapshot_request"
            is Payload.Unknown -> payload.type
        }

    public fun encode(envelope: Envelope): String {
        val payload = envelope.payload
        require(payload !is Payload.Unknown) { "Cannot encode unknown payload type ${typeOf(payload)}" }
        @Suppress("UNCHECKED_CAST")
        val serializer = registry.getValue(typeOf(payload)) as KSerializer<Payload>
        val obj =
            buildJsonObject {
                put("protocolVersion", envelope.protocolVersion.toString())
                put("type", typeOf(payload))
                put("stream", envelope.stream.wire)
                put("seq", envelope.seq)
                put("timestampMs", envelope.timestampMs)
                put("payload", json.encodeToJsonElement(serializer, payload))
            }
        return obj.toString()
    }

    /** @throws ProtocolException on unsupported major versions or malformed/missing critical fields. */
    public fun decode(text: String): Envelope {
        val root: JsonObject =
            try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: IllegalArgumentException) {
                throw ProtocolException.Malformed("Not a JSON object", e)
            } catch (e: SerializationException) {
                throw ProtocolException.Malformed("Invalid JSON", e)
            }
        val versionText = root.string("protocolVersion")
        val version = ProtocolVersion.parse(versionText)
        if (version.major != ProtocolVersion.SUPPORTED_MAJOR) throw ProtocolException.UnsupportedVersion(versionText)

        val type = root.string("type")
        val stream =
            Stream.fromWire(root.string("stream"))
                ?: throw ProtocolException.Malformed("Unknown stream '${root.string("stream")}'")
        val seq = root.long("seq")
        if (seq < 1) throw ProtocolException.Malformed("seq must be >= 1")
        val timestampMs = root.long("timestampMs")
        val payloadElement: JsonElement = root["payload"] ?: throw ProtocolException.Malformed("Missing 'payload'")
        if (payloadElement !is JsonObject) throw ProtocolException.Malformed("'payload' must be an object")

        val serializer = registry[type]
        val payload: Payload =
            if (serializer == null) {
                Payload.Unknown(type)
            } else {
                try {
                    json.decodeFromJsonElement(serializer, payloadElement)
                } catch (e: SerializationException) {
                    throw ProtocolException.Malformed("Invalid '$type' payload: ${e.message}", e)
                } catch (e: IllegalArgumentException) {
                    throw ProtocolException.Malformed("Invalid '$type' payload: ${e.message}", e)
                }
            }
        return Envelope(version, type, stream, seq, timestampMs, payload)
    }

    private fun JsonObject.string(key: String): String {
        val el = this[key] as? JsonPrimitive ?: throw ProtocolException.Malformed("Missing '$key'")
        if (!el.isString) throw ProtocolException.Malformed("'$key' must be a string")
        return el.contentOrNull ?: throw ProtocolException.Malformed("Missing '$key'")
    }

    private fun JsonObject.long(key: String): Long {
        val el = this[key] as? JsonPrimitive ?: throw ProtocolException.Malformed("Missing '$key'")
        return el.longOrNull ?: throw ProtocolException.Malformed("'$key' must be an integer")
    }
}
