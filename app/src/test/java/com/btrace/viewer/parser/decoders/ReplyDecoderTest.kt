package com.btrace.viewer.parser.decoders

import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.model.BinderDev
import com.btrace.viewer.parser.MethodResolver
import com.btrace.viewer.parser.MethodSignature
import com.btrace.viewer.parser.TransactionPairer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * spec § 3.2.2 + § 6.3.1 第 0 档:reply 帧识别 + spec 2026-05-03 § 3.5 接口对齐。
 */
class ReplyDecoderTest {

    private val decoder = ReplyDecoder()

    private fun ev(isReply: Boolean, pairId: Long = 0L): BinderEvent =
        BinderEvent(
            id = 0, timestamp = 0L, pid = 1, uid = 1, code = 1, flags = 0,
            rawParcel = ByteArray(0),
            isReply = isReply,
            pairId = pairId
        )

    @Test
    fun reply_orphan_pairId_zero() {
        val r = decoder.tryDecode(ev(isReply = true, pairId = 0L))
        assertNotNull(r)
        assertEquals("(reply)", r!!.interfaceName)
        assertEquals("← reply (orphan)", r.methodHint)
        assertEquals(DecodeSource.REPLY, r.source)
        assertEquals(Confidence.HIGH, r.confidence)
        assertEquals(-1, r.payloadStart)
    }

    @Test
    fun reply_paired_pairId_nonZero() {
        val r = decoder.tryDecode(ev(isReply = true, pairId = 42L))
        assertNotNull(r)
        assertEquals("(reply)", r!!.interfaceName)
        assertEquals("← reply", r.methodHint)
    }

    @Test
    fun nonReply_returnsNull() {
        assertNull(decoder.tryDecode(ev(isReply = false)))
        assertNull(decoder.tryDecode(ev(isReply = false, pairId = 99L)))
    }

    // ─── spec 2026-05-09 § daemon-reply-uid-filter:orphan 路径仍兜底解析 parcel ───

    @Test
    fun reply_orphan_still_parses_parcel_for_exception() {
        // EX_SECURITY = -1(LE 4 字节: 0xFF 0xFF 0xFF 0xFF),后跟空 message(length=-1)
        val parcel = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // exception code = -1
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // length = -1 (null msg)
        )
        val event = BinderEvent(
            id = 0, timestamp = 0L, pid = 1, uid = 1, code = 1, flags = 0,
            rawParcel = parcel,
            isReply = true,
            pairId = 0L, // orphan
            binderDev = com.btrace.viewer.model.BinderDev.BINDER, // 普通 binder 才走 Java 异常头
        )

        decoder.tryDecode(event)

        // parsedReply 应已填入异常摘要(SecurityException),即使 pairId == 0
        val pr = event.parsedReply
        assertNotNull("orphan reply 也应解出 parsedReply", pr)
        assertEquals("SecurityException", pr!!.exception)
    }

    @Test
    fun reply_orphan_hwbinder_with_negative_status_does_not_misread_as_java_exception() {
        // HIDL/HWBINDER reply 前 4B 是 _hidl_status。如果走 ReplyParser 解,
        // 0xFFFFFFFF 会被识别成 EX_SECURITY=-1 → 误标成 SecurityException。
        // 修复后:HWBINDER 走 rawHexHint 兜底,不调 decodeJavaAidlReply。
        val parcel = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )
        val event = BinderEvent(
            id = 0, timestamp = 0L, pid = 1, uid = 1, code = 1, flags = 0,
            rawParcel = parcel,
            isReply = true,
            pairId = 0L,
            binderDev = com.btrace.viewer.model.BinderDev.HWBINDER,
        )

        decoder.tryDecode(event)

        val pr = event.parsedReply
        assertNotNull(pr)
        // 关键不变量:HWBINDER 不应被误标成任何 Java 异常
        assertNull("HWBINDER reply 不能被识别为 Java 异常", pr!!.exception)
        assertNull(pr.value)
        // rawHexHint 应有内容(8 字节字符串形式)
        assertEquals("FF FF FF FF FF FF FF FF", pr.rawHexHint)
    }

    @Test
    fun reply_orphan_vndbinder_skips_java_exception_parsing() {
        // 等价测试:VNDBINDER 也不走 Java AIDL 异常解析
        val parcel = byteArrayOf(0xFD.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val event = BinderEvent(
            id = 0, timestamp = 0L, pid = 1, uid = 1, code = 1, flags = 0,
            rawParcel = parcel,
            isReply = true,
            pairId = 0L,
            binderDev = com.btrace.viewer.model.BinderDev.VNDBINDER,
        )

        decoder.tryDecode(event)
        val pr = event.parsedReply
        assertNotNull(pr)
        assertNull("VNDBINDER reply 不能被识别为 Java 异常", pr!!.exception)
        assertEquals("FD FF FF FF", pr.rawHexHint)
    }

    @Test
    fun reply_orphan_empty_parcel_falls_back_to_raw_hex() {
        // 空 parcel:无法解异常头,parsedReply.rawHexHint = "" 但 exception/value 都是 null
        val event = BinderEvent(
            id = 0, timestamp = 0L, pid = 1, uid = 1, code = 1, flags = 0,
            rawParcel = ByteArray(0),
            isReply = true,
            pairId = 0L,
        )

        decoder.tryDecode(event)

        // ReplyParser 对空 parcel 走 rawHexHint 兜底,这里至少 parsedReply 非空
        // (UI 端 reply.exception/value/rawHexHint 三字段全空时不渲染对应行,
        //  详情页只显示 Parcel hex 区,等价于改前体感)
        assertNotNull("orphan reply 应至少有 parsedReply 容器", event.parsedReply)
    }

    // 回复要使用请求的 code 和候选包查签名。

    @Test
    fun reply_paired_usesRequestCodeAndCandidates() {
        // matched 命中 + 请求的 resolveCandidates = ["P", "Q"] →
        // MethodResolver.getMethodSignature 应收到 candidates=["P", "Q"]
        val pairer: TransactionPairer = mock()
        val matched = TransactionPairer.PairResult(
            interfaceName = "com.example.IFoo",
            methodName = "doSomething",
            code = 5,
            resolveCandidates = listOf("P", "Q"),
            decodeSource = DecodeSource.AIDL_Q,
        )
        whenever(pairer.tryMatchReply(any(), any())).thenReturn(matched)

        val resolver: MethodResolver = mock()
        whenever(resolver.getMethodSignature(any(), any(), any<List<String>>(), any()))
            .thenReturn(MethodSignature("doSomething", listOf("int"), "void"))

        val decoderUnderTest = ReplyDecoder(
            pairer = pairer,
            targetUidProvider = { 0 },
            methodResolver = resolver,
        )

        val event = BinderEvent(
            id = 1, timestamp = 0, pid = 1, uid = 1, code = 0, flags = 0,
            rawParcel = ByteArray(0),
            isReply = true, pairId = 99L,
            binderDev = BinderDev.BINDER,
        ).apply {
            resolveCandidates = listOf("server.package")
        }

        decoderUnderTest.tryDecode(event)

        // 验证 MethodResolver 收到 ["P", "Q"]
        val captor = argumentCaptor<List<String>>()
        verify(resolver).getMethodSignature(
            eq("com.example.IFoo"),
            eq(5),
            captor.capture(),
            any(),
        )
        assertEquals(listOf("P", "Q"), captor.firstValue)
    }

    @Test
    fun reply_paired_hidl_does_not_decode_status_as_java_exception() {
        val pairer = TransactionPairer()
        pairer.recordRequest(ev(isReply = false, pairId = 1).apply {
            interfaceName = "android.hardware.test@1.0::ITest"
            methodName = "getValue"
            decodeSource = DecodeSource.HIDL_DESCRIPTOR
        }, 1)
        val resolver: MethodResolver = mock()
        val event = ev(isReply = true, pairId = 1).copy(
            uid = 2,
            binderDev = BinderDev.HWBINDER,
            rawParcel = ByteArray(8) { -1 },
        )

        ReplyDecoder(pairer, { 1 }, resolver).tryDecode(event)

        assertNotNull(event.parsedReply)
        assertNull(event.parsedReply!!.exception)
        assertNull(event.parsedReply!!.value)
        assertEquals("FF FF FF FF FF FF FF FF", event.parsedReply!!.rawHexHint)
        verifyNoInteractions(resolver)
    }
}
