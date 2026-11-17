package capture.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** Verifies the Kotlin codec against the language-neutral contract in contracts/capture_protocol.json. */
class ContractTest {
    private val contract: JsonObject =
        Json.parseToJsonElement(File(System.getProperty("capture.contract")).readText()).jsonObject

    private fun strings(el: kotlinx.serialization.json.JsonElement?) = (el as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    @Test fun contractVersionMatchesCodec() {
        assertEquals(ProtocolVersion.Current.toString(), contract.getValue("protocolVersion").jsonPrimitive.content)
        assertEquals(
            ProtocolVersion.SUPPORTED_MAJOR,
            contract.getValue("compatibility").jsonObject.getValue("supportedMajor").jsonPrimitive.content.toInt(),
        )
    }

    @Test fun everyContractMessageTypeIsKnownToTheCodecAndViceVersa() {
        val types = contract.getValue("messages").jsonObject.keys
        assertEquals(types, ProtocolCodec.knownTypes)
    }

    @Test fun enumWireNamesMatchContract() {
        val enums = contract.getValue("enums").jsonObject

        fun <E : Enum<E>> wireNames(values: Array<E>, ser: kotlinx.serialization.KSerializer<E>) =
            values.map { ProtocolCodec.json.encodeToString(ser, it).trim('"') }
        assertEquals(strings(enums["modality"]), wireNames(WireModality.entries.toTypedArray(), WireModality.serializer()))
        assertEquals(strings(enums["trackState"]), wireNames(WireTrackState.entries.toTypedArray(), WireTrackState.serializer()))
        assertEquals(strings(enums["jobStatus"]), wireNames(WireJobStatus.entries.toTypedArray(), WireJobStatus.serializer()))
        assertEquals(strings(enums["sensorHealth"]), wireNames(WireSensorHealth.entries.toTypedArray(), WireSensorHealth.serializer()))
        assertEquals(strings(enums["calibration"]), wireNames(WireCalibration.entries.toTypedArray(), WireCalibration.serializer()))
        assertEquals(strings(enums["command"]), wireNames(WireCommand.entries.toTypedArray(), WireCommand.serializer()))
    }

    @Test fun requiredFieldsInContractAreRequiredInCodec() {
        // For each message, removing any single required payload field must make decoding fail.
        val messages = contract.getValue("messages").jsonObject
        val examples = contract.getValue("examples").jsonArray.map { it.jsonObject }
        for (example in examples) {
            val type = example.getValue("type").jsonPrimitive.content
            val required = strings(messages.getValue(type).jsonObject["required"])
            for (field in required) {
                val payload = JsonObject(example.getValue("payload").jsonObject - field)
                val broken = JsonObject(example + ("payload" to payload)).toString()
                assertFailsWith<ProtocolException.Malformed>("'$type' without '$field' must be rejected") { ProtocolCodec.decode(broken) }
            }
        }
    }

    @Test fun allExamplesDecodeAndRoundTrip() {
        val examples = contract.getValue("examples").jsonArray
        assertTrue(examples.size >= 5)
        for (example in examples) {
            val env = ProtocolCodec.decode(example.toString())
            assertEquals(example.jsonObject.getValue("type").jsonPrimitive.content, env.type)
            val reencoded = ProtocolCodec.decode(ProtocolCodec.encode(env.copy(protocolVersion = ProtocolVersion.Current)))
            assertEquals(env.payload, reencoded.payload)
            assertEquals(env.seq, reencoded.seq)
            assertEquals(env.timestampMs, reencoded.timestampMs)
        }
    }

    @Test fun higherMinorVersionAndUnknownOptionalFieldsAreAccepted() {
        val example =
            contract.getValue("examples").jsonArray.map { it.jsonObject }.first {
                it.getValue("protocolVersion").jsonPrimitive.content ==
                    "1.1"
            }
        val env = ProtocolCodec.decode(example.toString())
        assertEquals(ProtocolVersion(1, 1), env.protocolVersion)
        assertIs<Payload.SensorUpdate>(env.payload)
    }

    @Test fun rejectedExamplesAreRejected() {
        for (rejected in contract.getValue("rejectedExamples").jsonArray) {
            val why = rejected.jsonObject.getValue("why").jsonPrimitive.content
            try {
                ProtocolCodec.decode(rejected.jsonObject.getValue("message").toString())
                fail("Expected rejection: $why")
            } catch (e: ProtocolException) {
                if (why.contains("major")) assertIs<ProtocolException.UnsupportedVersion>(e, why)
            }
        }
    }

    @Test fun unknownMessageTypeIsSurfacedNotRejected() {
        val text = """{"protocolVersion":"1.3","type":"zoom_hint","stream":"state","seq":7,"timestampMs":5,"payload":{"x":1}}"""
        val env = ProtocolCodec.decode(text)
        assertEquals(Payload.Unknown("zoom_hint"), env.payload)
        assertEquals(7, env.seq)
    }

    @Test fun wireFormatContainsNoProductBranding() {
        val text = contract.toString().lowercase()
        assertTrue("protagonist" !in text, "Wire protocol must stay product-name neutral")
    }
}
