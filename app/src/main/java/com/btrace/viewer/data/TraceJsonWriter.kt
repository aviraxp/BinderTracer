package com.btrace.viewer.data

import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.model.StackFrame
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

    fun writeEvent(event: BinderEvent) {
        if (!firstEvent) writer.write(",\n")
        firstEvent = false
        writer.write(toJson(event).toString(2))
    }

    fun finish(count: Long) {
        writer.write("\n  ],\n  \"eventCount\": $count\n}\n")
        writer.flush()
    }

    private fun toJson(e: BinderEvent): JSONObject = JSONObject().apply {
        // Store 64-bit integers as strings so tools such as JavaScript retain every bit.
        put("id", e.id.toString())
        put("timestampNs", e.timestamp.toString())
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
        put("decodeSource", e.decodeSource?.name ?: JSONObject.NULL)
        put("confidence", e.confidence?.name ?: JSONObject.NULL)
        put("parcelSize", e.rawParcel.size)
        put("rawParcelBase64", Base64.getEncoder().encodeToString(e.rawParcel))
        put("parsedArgs", JSONArray().apply {
            for (arg in e.parsedArgs) put(JSONObject().apply {
                put("index", arg.index)
                put("declaredType", arg.declaredType)
                put("displayValue", arg.displayValue)
                put("status", arg.status.name)
                put("errorMessage", arg.errorMessage ?: JSONObject.NULL)
            })
        })
        put("parsedReply", e.parsedReply?.let { reply ->
            JSONObject().apply {
                put("exception", reply.exception ?: JSONObject.NULL)
                put("value", reply.value ?: JSONObject.NULL)
                put("rawHexHint", reply.rawHexHint ?: JSONObject.NULL)
            }
        } ?: JSONObject.NULL)
        put("sniffedSignature", JSONArray(e.sniffedSignature))
        put("resolveCandidates", JSONArray(e.resolveCandidates))
        put("stackTrace", e.stackTrace?.let { stack ->
            JSONObject().apply {
                put("quality", stack.quality.name)
                put("truncated", stack.truncated)
                put("failureReason", stack.failureReason)
                put("kernelFrames", frames(stack.kFrames))
                put("userFrames", frames(stack.uFrames))
            }
        } ?: JSONObject.NULL)
    }

    private fun frames(frames: List<StackFrame>): JSONArray = JSONArray().apply {
        for (frame in frames) put(JSONObject().apply {
            put("pc", hex(frame.pc))
            put("module", frame.module)
            put("symbol", frame.symbol)
            put("offset", hex(frame.offset))
        })
    }

    private fun hex(value: Long): String = "0x${java.lang.Long.toUnsignedString(value, 16)}"
}
