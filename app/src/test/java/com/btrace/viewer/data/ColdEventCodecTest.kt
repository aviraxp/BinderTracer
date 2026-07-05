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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ColdEventCodec] + [EventPayloadJson] round-trip 单测:完整归档不能静默丢字段
 * (源数据 + 解析结果 + 调用栈 + 参数值都要 round-trip)。
 * 用 Robolectric 因为 payload 走 org.json(Android runtime)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ColdEventCodecTest {

    private fun sample() = BinderEvent(
        id = 42L,
        timestamp = 123_456_789L,
        pid = 1000,
        uid = 10086,
        code = 7,
        flags = 0x11,
        rawParcel = byteArrayOf(1, 2, 3, 4, 5),
        isReply = true,
        binderDev = BinderDev.BINDER,
        targetKind = TargetKind.HANDLE,
        toPid = 2000,
        toUid = 1000,
        targetRef = 0xdeadbeefL,
        pairId = 99L,
        stackTrace = StackTrace(
            quality = StackQuality.FULL,
            truncated = 1,
            kFrames = listOf(StackFrame(0x1000L, "libc.so", "memcpy", 0x10L)),
            uFrames = listOf(StackFrame(0x2000L, "libgui.so", "queueBuffer", 0x20L)),
            failureReason = "",
        ),
    ).apply {
        interfaceName = "android.app.IActivityManager"
        methodName = "startActivity"
        callerPackage = "com.example.app"
        toPackage = "system"
        direction = Direction.OUTGOING_REQUEST
        decodeSource = DecodeSource.AIDL_Q
        confidence = Confidence.HIGH
        parsedArgs = listOf(
            DecodedArgument(0, "int", "42", DecodedArgument.Status.SUCCESS),
            DecodedArgument(1, "String", "hi", DecodedArgument.Status.SUCCESS, null),
        )
        parsedReply = ReplyParser.ReplyDecodeResult(exception = null, value = "0", rawHexHint = null)
        sniffedSignature = listOf("String", "IBinder")
        resolveCandidates = listOf("com.example.app", "com.other")
    }

    private fun roundTrip(e: BinderEvent) = ColdEventCodec.fromRow(ColdEventCodec.toRow(e))

    @Test
    fun `round trips core scalar fields and rawParcel`() {
        val r = roundTrip(sample())
        val s = sample()
        assertEquals(s.id, r.id)
        assertEquals(s.timestamp, r.timestamp)
        assertEquals(s.pid, r.pid)
        assertEquals(s.uid, r.uid)
        assertEquals(s.code, r.code)
        assertEquals(s.flags, r.flags)
        assertEquals(s.isReply, r.isReply)
        assertEquals(s.binderDev, r.binderDev)
        assertEquals(s.targetKind, r.targetKind)
        assertEquals(s.toPid, r.toPid)
        assertEquals(s.toUid, r.toUid)
        assertEquals(s.targetRef, r.targetRef)
        assertEquals(s.pairId, r.pairId)
        assertArrayEquals(s.rawParcel, r.rawParcel)
    }

    @Test
    fun `round trips parsed display fields and enums`() {
        val r = roundTrip(sample())
        assertEquals("android.app.IActivityManager", r.interfaceName)
        assertEquals("startActivity", r.methodName)
        assertEquals("com.example.app", r.callerPackage)
        assertEquals("system", r.toPackage)
        assertEquals(Direction.OUTGOING_REQUEST, r.direction)
        assertEquals(DecodeSource.AIDL_Q, r.decodeSource)
        assertEquals(Confidence.HIGH, r.confidence)
    }

    @Test
    fun `round trips stack trace`() {
        val st = roundTrip(sample()).stackTrace!!
        assertEquals(StackQuality.FULL, st.quality)
        assertEquals(1, st.truncated)
        assertEquals("memcpy", st.kFrames[0].symbol)
        assertEquals(0x1000L, st.kFrames[0].pc)
        assertEquals("libgui.so", st.uFrames[0].module)
        assertEquals(0x20L, st.uFrames[0].offset)
    }

    @Test
    fun `round trips parsed args reply signatures candidates`() {
        val r = roundTrip(sample())
        assertEquals(2, r.parsedArgs.size)
        assertEquals("42", r.parsedArgs[0].displayValue)
        assertEquals(DecodedArgument.Status.SUCCESS, r.parsedArgs[1].status)
        assertEquals("0", r.parsedReply!!.value)
        assertEquals(listOf("String", "IBinder"), r.sniffedSignature)
        assertEquals(listOf("com.example.app", "com.other"), r.resolveCandidates)
    }

    @Test
    fun `stack modules column concatenates frame modules`() {
        val mods = ColdEventCodec.stackModules(sample())!!
        assertTrue(mods.contains("libgui.so"))
        assertTrue(mods.contains("libc.so"))
    }

    @Test
    fun `no stack trace round trips to null and no modules`() {
        val e = BinderEvent(1L, 0L, 1, 1, 1, 0, ByteArray(0))
        val r = roundTrip(e)
        assertNull(r.stackTrace)
        assertNull(ColdEventCodec.stackModules(e))
    }

    @Test
    fun `unknown enum name falls back instead of throwing`() {
        val row = ColdEventCodec.toRow(sample()).toMutableMap()
        row[ColdEventCodec.COL_DIRECTION] = "SOME_FUTURE_VALUE"
        row[ColdEventCodec.COL_BINDER_DEV] = "SOME_FUTURE_DEV"
        val r = ColdEventCodec.fromRow(row)
        assertEquals(Direction.UNKNOWN, r.direction)
        assertEquals(BinderDev.UNKNOWN, r.binderDev)
    }
}
