package com.btrace.viewer.data

import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.model.StackFrame
import com.btrace.viewer.model.isOneway
import com.btrace.viewer.parser.DecodedArgument
import com.btrace.viewer.parser.ReplyParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.Writer
import java.time.Instant
import java.util.Base64

/** Build one event at a time instead of keeping the whole trace in memory. */
internal class TraceJsonWriter(private val writer: Writer) {
    private var firstEvent = true

    init {
        writer.write("{\n  \"format\": \"BinderTracer\",\n  \"version\": 1,\n")
        writer.write("  \"exportedAt\": ${JSONObject.quote(Instant.now().toString())},\n")
        writer.write("  \"events\": [\n")
    }

    fun writeEvent(event: BinderEvent, linkedEvent: BinderEvent? = null) {
        if (!firstEvent) writer.write(",\n")
        firstEvent = false
        writer.write(toJson(event, linkedEvent).toString(2))
    }

    fun finish(count: Long) {
        writer.write("\n  ],\n  \"eventCount\": $count\n}\n")
        writer.flush()
    }

    private fun toJson(e: BinderEvent, linkedEvent: BinderEvent?): JSONObject = JSONObject().apply {
        // Store 64-bit integers as strings so tools such as JavaScript retain every bit.
        put("id", e.id.toString())
        put("timestampNs", e.timestamp.toString())
        put("formattedTime", e.formattedFullTime)
        put("pairId", e.pairId.toString())
        put("pid", e.pid)
        put("uid", e.uid)
        put("toPid", e.toPid)
        put("toUid", e.toUid)
        put("code", e.code)
        put("flags", e.flags)
        put("isReply", e.isReply)
        put("binderDev", e.binderDev.name)
        put("targetKind", e.targetKind.name)
        put("targetRef", hex(e.targetRef))
        put("interfaceName", e.interfaceName)
        put("methodName", e.methodName)
        put("callerPackage", e.callerPackage)
        put("toPackage", e.toPackage ?: JSONObject.NULL)
        put("direction", e.direction.name)
        put("directionLabel", e.directionLabel)
        put("callMode", if (e.isReply) "reply" else if (isOneway(e.flags)) "oneway" else "twoway")
        put("flagsHex", "0x${Integer.toUnsignedString(e.flags, 16).padStart(8, '0')}")
        put("decodeSource", e.decodeSource?.name ?: JSONObject.NULL)
        put("confidence", e.confidence?.name ?: JSONObject.NULL)
        put("parcelSize", e.rawParcel.size)
        put("parsedArgs", args(e.parsedArgs))
        put("parsedReply", e.parsedReply?.let(::reply) ?: JSONObject.NULL)
        put("sniffedSignature", JSONArray(e.sniffedSignature))
        put("resolveCandidates", JSONArray(e.resolveCandidates))
        // Use the same request/response view as the detail page.
        val request = if (e.isReply) linkedEvent else e
        val response = if (e.isReply) e else linkedEvent
        put("request", request?.let { req ->
            JSONObject().apply {
                put("eventId", req.id.toString())
                put("interfaceName", req.interfaceName)
                put("methodName", req.methodName)
                put("parsedArgs", args(req.parsedArgs))
                put("sniffedSignature", JSONArray(req.sniffedSignature))
                put("parcelSize", req.rawParcel.size)
            }
        } ?: JSONObject.NULL)
        put("response", response?.let { res ->
            JSONObject().apply {
                put("eventId", res.id.toString())
                put("parsedReply", res.parsedReply?.let(::reply) ?: JSONObject.NULL)
                put("parcelSize", res.rawParcel.size)
                put("latencyMs", request?.let { req -> (res.timestamp - req.timestamp) / 1_000_000.0 } ?: JSONObject.NULL)
            }
        } ?: JSONObject.NULL)
        put("responseStatus", when {
            response != null -> "received"
            isOneway(e.flags) -> "oneway"
            e.pairId == 0L -> "unpaired"
            else -> "notCaptured"
        })
        put("stackTrace", e.stackTrace?.let { stack ->
            JSONObject().apply {
                put("quality", stack.quality.name)
                put("truncated", stack.truncated)
                put("failureReason", stack.failureReason)
                put("kernelFrames", frames(stack.kFrames))
                put("userFrames", frames(stack.uFrames))
            }
        } ?: JSONObject.NULL)
        put("rawParcelBase64", Base64.getEncoder().encodeToString(e.rawParcel))
    }

    private fun args(args: List<DecodedArgument>): JSONArray = JSONArray().apply {
        for (arg in args) put(JSONObject().apply {
            put("index", arg.index)
            put("declaredType", arg.declaredType)
            put("displayValue", arg.displayValue)
            put("status", arg.status.name)
            put("errorMessage", arg.errorMessage ?: JSONObject.NULL)
        })
    }

    private fun reply(reply: ReplyParser.ReplyDecodeResult): JSONObject = JSONObject().apply {
        put("exception", reply.exception ?: JSONObject.NULL)
        put("value", reply.value ?: JSONObject.NULL)
        put("rawHexHint", reply.rawHexHint ?: JSONObject.NULL)
    }

    private fun frames(frames: List<StackFrame>): JSONArray = JSONArray().apply {
        for (frame in frames) put(JSONObject().apply {
            put("displayText", frame.displayText())
            put("pc", hex(frame.pc))
            put("module", frame.module)
            put("symbol", frame.symbol)
            put("offset", hex(frame.offset))
        })
    }

    private fun hex(value: Long): String = "0x${java.lang.Long.toUnsignedString(value, 16)}"
}
