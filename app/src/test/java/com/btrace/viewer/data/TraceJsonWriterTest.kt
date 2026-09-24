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
        assertEquals(event.formattedFullTime, json.getString("formattedTime"))
        assertEquals("← 回复 (入向)", json.getString("directionLabel"))
        assertEquals("reply", json.getString("callMode"))
        assertEquals("0x80000000", json.getString("flagsHex"))
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
        assertEquals("libbinder.so!transact + 0x20", stack.getJSONArray("userFrames").getJSONObject(0).getString("displayText"))
        assertTrue(json.isNull("request"))
        assertEquals("received", json.getString("responseStatus"))
        assertTrue(json.getJSONObject("response").isNull("latencyMs"))
        assertEquals("example.Error", json.getJSONObject("response").getJSONObject("parsedReply").getString("exception"))
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
        assertEquals("unpaired", first.getString("responseStatus"))
        assertTrue(first.isNull("response"))
    }

    @Test
    fun `distinguishes oneway requests from missing replies`() {
        val output = StringWriter()
        TraceJsonWriter(output).apply {
            writeEvent(BinderEvent(0, 0, 1, 1, 1, 1, byteArrayOf(), pairId = 7))
            writeEvent(BinderEvent(1, 0, 1, 1, 1, 0, byteArrayOf(), pairId = 8))
            finish(2)
        }
        val events = JSONObject(output.toString()).getJSONArray("events")
        assertEquals("oneway", events.getJSONObject(0).getString("responseStatus"))
        assertEquals("oneway", events.getJSONObject(0).getString("callMode"))
        assertEquals("notCaptured", events.getJSONObject(1).getString("responseStatus"))
        assertEquals("twoway", events.getJSONObject(1).getString("callMode"))
        assertTrue(events.getJSONObject(0).isNull("response"))
        assertTrue(events.getJSONObject(1).isNull("response"))
    }
}
