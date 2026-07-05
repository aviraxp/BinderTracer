package com.btrace.viewer.data

import android.content.Context
import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.parser.InterfaceIndex
import com.btrace.viewer.parser.MethodResolver
import com.btrace.viewer.parser.ParcelArgumentDecoder
import com.btrace.viewer.parser.ParcelParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec 2026-05-03 § 3.2:[EventRepository.computeResolveCandidatesForTest] 验证候选
 * 包顺序、跨来源去重、空值跳过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EventRepositoryTest {

    private lateinit var context: Context
    private lateinit var repo: EventRepository
    private lateinit var index: InterfaceIndex

    @Before
    fun setUp() {
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        index = InterfaceIndex(context)
        repo = EventRepository(
            parcelParser = mock<ParcelParser>(),
            methodResolver = mock<MethodResolver>(),
            argumentDecoder = mock<ParcelArgumentDecoder>(),
            appRepository = AppRepository(context),
            interfaceIndex = index,
            serviceManagerCatalog = com.btrace.viewer.parser.ServiceManagerCatalog(),
        )
    }

    @Test
    fun `candidates ordered targetPackage then index then sender`() {
        index.addEntry("com.foo.IFoo", "com.idx.a")
        index.addEntry("com.foo.IFoo", "com.idx.b")

        val out = repo.computeResolveCandidatesForTest(
            senderPkg = "com.sender",
            interfaceName = "com.foo.IFoo",
            targetPackageOverride = "com.target",
        )
        // P1=target, P2-P3=index 顺序, P4=sender
        assertEquals(listOf("com.target", "com.idx.a", "com.idx.b", "com.sender"), out)
    }

    @Test
    fun `candidates dedup target equals index hit keeps target position`() {
        // target = P,index = [P, Q],sender = P → 去重保留 [P, Q]
        index.addEntry("X.Y.Z", "P")
        index.addEntry("X.Y.Z", "Q")

        val out = repo.computeResolveCandidatesForTest(
            senderPkg = "P",
            interfaceName = "X.Y.Z",
            targetPackageOverride = "P",
        )
        assertEquals(listOf("P", "Q"), out)
    }

    @Test
    fun `candidates without targetPackage falls back to index plus sender`() {
        index.addEntry("X.Y.Z", "I1")
        val out = repo.computeResolveCandidatesForTest(
            senderPkg = "S",
            interfaceName = "X.Y.Z",
            targetPackageOverride = null, // 默认 null,setTargetUid 未触发反查
        )
        assertEquals(listOf("I1", "S"), out)
    }

    @Test
    fun `candidates without sender skips sender slot`() {
        index.addEntry("X.Y.Z", "I1")
        val out = repo.computeResolveCandidatesForTest(
            senderPkg = null,
            interfaceName = "X.Y.Z",
            targetPackageOverride = "T",
        )
        assertEquals(listOf("T", "I1"), out)
    }

    @Test
    fun `candidates empty when all sources missing`() {
        val out = repo.computeResolveCandidatesForTest(
            senderPkg = null,
            interfaceName = null,
            targetPackageOverride = null,
        )
        assertEquals(emptyList<String>(), out)
    }

    @Test
    fun `candidates skip empty strings`() {
        index.addEntry("X.Y.Z", "I1")
        val out = repo.computeResolveCandidatesForTest(
            senderPkg = "",
            interfaceName = "X.Y.Z",
            targetPackageOverride = "",
        )
        // target / sender 都是空串 → 跳过,只保留 index 命中
        assertEquals(listOf("I1"), out)
    }

    @Test
    fun `candidates skip index lookup when interface name null`() {
        // interfaceName=null → InterfaceIndex.lookupOrdered 不查询;只剩 target + sender
        val out = repo.computeResolveCandidatesForTest(
            senderPkg = "S",
            interfaceName = null,
            targetPackageOverride = "T",
        )
        assertEquals(listOf("T", "S"), out)
    }

    // === spec 2026-05-09 § 3:findReplyForRequest 行为验证 ===

    private fun newEvent(
        id: Long,
        isReply: Boolean,
        pairId: Long,
        timestamp: Long = id * 1_000_000L,
    ): BinderEvent = BinderEvent(
        id = id,
        timestamp = timestamp,
        pid = 1,
        uid = 1000,
        code = 1,
        flags = 0,
        rawParcel = ByteArray(0),
        isReply = isReply,
        pairId = pairId,
    )

    @Test
    fun `findReplyForRequest returns null when request itself is reply`() {
        val reply = newEvent(id = 1, isReply = true, pairId = 100L)
        repo.addEventDirectForTest(reply)
        // reply 不再有 reply。
        assertNull(runBlocking { repo.findReplyForRequest(reply) })
    }

    @Test
    fun `findReplyForRequest returns null when pairId is zero`() {
        val request = newEvent(id = 1, isReply = false, pairId = 0L)
        val replyOrphan = newEvent(id = 2, isReply = true, pairId = 0L)
        repo.addEventDirectForTest(request)
        repo.addEventDirectForTest(replyOrphan)
        // pairId == 0:daemon 端无配对能力,即便 buffer 里有同 pairId=0 的 reply 也不算配对。
        assertNull(runBlocking { repo.findReplyForRequest(request) })
    }

    @Test
    fun `findReplyForRequest matches reply with same pairId`() {
        val request = newEvent(id = 1, isReply = false, pairId = 42L)
        val reply = newEvent(id = 2, isReply = true, pairId = 42L)
        repo.addEventDirectForTest(request)
        repo.addEventDirectForTest(reply)

        val matched = runBlocking { repo.findReplyForRequest(request) }
        assertNotNull(matched)
        assertSame(reply, matched)
    }

    @Test
    fun `findReplyForRequest returns null when reply not yet arrived`() {
        val request = newEvent(id = 1, isReply = false, pairId = 42L)
        repo.addEventDirectForTest(request)
        // 还没配对 reply 到达。
        assertNull(runBlocking { repo.findReplyForRequest(request) })
    }

    @Test
    fun `findReplyForRequest ignores reply with different pairId`() {
        val request = newEvent(id = 1, isReply = false, pairId = 42L)
        val unrelatedReply = newEvent(id = 2, isReply = true, pairId = 99L)
        repo.addEventDirectForTest(request)
        repo.addEventDirectForTest(unrelatedReply)
        assertNull(runBlocking { repo.findReplyForRequest(request) })
    }

    @Test
    fun `findReplyForRequest picks earliest reply if duplicates`() {
        // 极端情况:daemon 上报重复 reply(协议事实上不应出现,但兜底要求行为可预测)。
        val request = newEvent(id = 1, isReply = false, pairId = 42L)
        val reply1 = newEvent(id = 2, isReply = true, pairId = 42L, timestamp = 2_000_000L)
        val reply2 = newEvent(id = 3, isReply = true, pairId = 42L, timestamp = 3_000_000L)
        repo.addEventDirectForTest(request)
        repo.addEventDirectForTest(reply1)
        repo.addEventDirectForTest(reply2)

        // FIFO 顺序遍历 → 取先入队的那条,与"幂等优先"语义一致。
        assertSame(reply1, runBlocking { repo.findReplyForRequest(request) })
    }

    // === reply → request 反向 lookup 行为验证 ===

    @Test
    fun `findRequestForReply returns null when event is request`() {
        val request = newEvent(id = 1, isReply = false, pairId = 42L)
        repo.addEventDirectForTest(request)
        assertNull(runBlocking { repo.findRequestForReply(request) })
    }

    @Test
    fun `findRequestForReply returns null when pairId is zero`() {
        val replyOrphan = newEvent(id = 1, isReply = true, pairId = 0L)
        val request = newEvent(id = 2, isReply = false, pairId = 0L)
        repo.addEventDirectForTest(replyOrphan)
        repo.addEventDirectForTest(request)
        // orphan reply(无配对 pairId)即便 buffer 里有 pairId=0 的 request 也不算配对。
        assertNull(runBlocking { repo.findRequestForReply(replyOrphan) })
    }

    @Test
    fun `findRequestForReply matches request with same pairId`() {
        val request = newEvent(id = 1, isReply = false, pairId = 7549852L)
        val reply = newEvent(id = 2, isReply = true, pairId = 7549852L)
        repo.addEventDirectForTest(request)
        repo.addEventDirectForTest(reply)

        val matched = runBlocking { repo.findRequestForReply(reply) }
        assertNotNull(matched)
        assertSame(request, matched)
    }

    @Test
    fun `findRequestForReply returns null when request already evicted`() {
        // request 被 FIFO 淘汰、reply 留下来 → 反向 lookup 返回 null,UI 退化到
        // 「配对 request 已被 FIFO 淘汰」分支。
        val orphanedReply = newEvent(id = 1, isReply = true, pairId = 42L)
        repo.addEventDirectForTest(orphanedReply)
        assertNull(runBlocking { repo.findRequestForReply(orphanedReply) })
    }

    @Test
    fun `findRequestForReply ignores request with different pairId`() {
        val unrelatedRequest = newEvent(id = 1, isReply = false, pairId = 99L)
        val reply = newEvent(id = 2, isReply = true, pairId = 42L)
        repo.addEventDirectForTest(unrelatedRequest)
        repo.addEventDirectForTest(reply)
        assertNull(runBlocking { repo.findRequestForReply(reply) })
    }
}
