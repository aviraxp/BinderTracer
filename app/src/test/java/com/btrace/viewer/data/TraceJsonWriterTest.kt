package com.btrace.viewer.data

import com.btrace.viewer.model.BinderDev
import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.model.Direction
import com.btrace.viewer.model.StackFrame
import com.btrace.viewer.model.StackQuality
import com.btrace.viewer.model.StackTrace
import com.btrace.viewer.model.TargetKind
import com.btrace.viewer.parser.DecodedArgument
import com.btrace.viewer.parser.ReplyParser
import com.btrace.viewer.parser.decoders.Confidence
import com.btrace.viewer.parser.decoders.DecodeSource
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter
import java.util.Base64

class TraceJsonWriterTest {
    @Test
    fun `preserves parcel bytes precise integers and decoded fields`() {
        val parcel = byteArrayOf(0, 1, 127, -128, -1)
        val event = BinderEvent(
            id = 0,
            timestamp = 1_789_876_543_123_456_789L,
            pid = 123,
            uid = 10123,
            code = 42,
            flags = Int.MIN_VALUE,
            rawParcel = parcel,
            isReply = true,
            binderDev = BinderDev.BINDER,
            targetKind = TargetKind.PTR,
            toPid = 456,
            toUid = 1000,
            targetRef = Long.MIN_VALUE,
            pairId = Long.MAX_VALUE,
            stackTrace = StackTrace(
                StackQuality.DEGRADED, 3,
                listOf(StackFrame(-1L, "kernel", "binder_transaction", 16)),
                listOf(StackFrame(123L, "/system/lib64/libbinder.so", "transact", 32)),
                "partial stack",
            ),
        ).apply {
            interfaceName = "example.ITest"
            methodName = "send"
            callerPackage = "example.client"
            toPackage = "system"
            direction = Direction.INCOMING_REPLY
            decodeSource = DecodeSource.REPLY
            confidence = Confidence.HIGH
            parsedArgs = listOf(DecodedArgument(
                0, "String", "quote: \" newline: \n unicode: \u6d4b\u8bd5",
                DecodedArgument.Status.UNPARSED, "unknown type",
            ))
            parsedReply = ReplyParser.ReplyDecodeResult("example.Error", "value", "00 ff")
            sniffedSignature = listOf("String")
            resolveCandidates = listOf("example.client")
        }
        val output = StringWriter()
        TraceJsonWriter(output).apply {
            writeEvent(event)
            finish(1)
        }

        val root = JSONObject(output.toString())
        assertEquals("BinderTracer", root.getString("format"))
        assertEquals(1, root.getInt("version"))
        assertEquals(1L, root.getLong("eventCount"))
        val json = root.getJSONArray("events").getJSONObject(0)
        assertEquals("0", json.getString("id"))
        assertEquals(event.timestamp.toString(), json.get("timestampNs"))
        assertEquals(Long.MAX_VALUE.toString(), json.get("pairId"))
        assertEquals("0x8000000000000000", json.getString("targetRef"))
        assertArrayEquals(parcel, Base64.getDecoder().decode(json.getString("rawParcelBase64")))
        assertEquals(parcel.size, json.getInt("parcelSize"))
        assertTrue(json.getBoolean("isReply"))
        assertEquals(event.flags, json.getInt("flags"))
        assertEquals("example.ITest", json.getString("interfaceName"))
        assertEquals("send", json.getString("methodName"))
        assertEquals("system", json.getString("toPackage"))
        assertEquals("INCOMING_REPLY", json.getString("direction"))
        assertEquals("REPLY", json.getString("decodeSource"))
        assertEquals("HIGH", json.getString("confidence"))
        val arg = json.getJSONArray("parsedArgs").getJSONObject(0)
        assertEquals(event.parsedArgs[0].displayValue, arg.getString("displayValue"))
        assertEquals("unknown type", arg.getString("errorMessage"))
        assertEquals("example.Error", json.getJSONObject("parsedReply").getString("exception"))
        val stack = json.getJSONObject("stackTrace")
        assertEquals(3, stack.getInt("truncated"))
        assertEquals("partial stack", stack.getString("failureReason"))
        assertEquals("0xffffffffffffffff", stack.getJSONArray("kernelFrames").getJSONObject(0).getString("pc"))
        assertEquals("/system/lib64/libbinder.so", stack.getJSONArray("userFrames").getJSONObject(0).getString("module"))
        assertEquals("String", json.getJSONArray("sniffedSignature").getString(0))
        assertEquals("example.client", json.getJSONArray("resolveCandidates").getString(0))
    }

    @Test
    fun `writes multiple events and explicit nulls`() {
        val output = StringWriter()
        TraceJsonWriter(output).apply {
            repeat(2) { id ->
                writeEvent(BinderEvent(id.toLong(), 0, 0, 0, 0, 0, byteArrayOf()))
            }
            finish(2)
        }
        val root = JSONObject(output.toString())
        val events = root.getJSONArray("events")
        assertEquals(2, events.length())
        assertEquals("1", events.getJSONObject(1).getString("id"))
        val first = events.getJSONObject(0)
        for (field in listOf("stackTrace", "parsedReply", "toPackage", "decodeSource", "confidence")) {
            assertTrue(first.has(field))
            assertTrue(first.isNull(field))
        }
        assertEquals("", first.getString("rawParcelBase64"))
        assertEquals(0, first.getJSONArray("parsedArgs").length())
    }
}
