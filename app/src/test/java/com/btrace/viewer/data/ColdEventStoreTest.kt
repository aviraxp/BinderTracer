package com.btrace.viewer.data

import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.model.StackFrame
import com.btrace.viewer.model.StackQuality
import com.btrace.viewer.model.StackTrace
import com.btrace.viewer.parser.CoverageBucket
import com.btrace.viewer.parser.decoders.DecodeSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [ColdEventStore] 检索路径单测:过滤条件下推 SQL 的正确性 + 键集游标分页。
 * 用同步 writeNowForTest 落盘(绕开异步 channel),专注 query 的 SQL 逻辑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ColdEventStoreTest {

    private lateinit var store: ColdEventStore

    @Before
    fun setUp() {
        // onOpen 每次打开清空表,保证测试间隔离。
        store = ColdEventStore(RuntimeEnvironment.getApplication())
    }

    private fun ev(
        id: Long,
        iface: String = "android.app.IActivityManager",
        method: String = "startActivity",
        source: DecodeSource? = DecodeSource.AIDL_Q,
        isReply: Boolean = false,
        pairId: Long = 0L,
        modules: List<String> = emptyList(),
    ) = BinderEvent(
        id = id, timestamp = id, pid = 1, uid = 1, code = 1, flags = 0,
        rawParcel = ByteArray(0), isReply = isReply, pairId = pairId,
        stackTrace = if (modules.isEmpty()) null else StackTrace(
            StackQuality.FULL, 0, emptyList(),
            modules.mapIndexed { i, m -> StackFrame(i.toLong(), m, "", 0L) },
        ),
    ).apply {
        interfaceName = iface
        methodName = method
        decodeSource = source
    }

    @Test
    fun `getById returns written event`() = runBlocking {
        store.writeNowForTest(listOf(ev(1), ev(2)))
        assertEquals(2L, store.getById(2)!!.id)
        assertNull(store.getById(99))
    }

    @Test
    fun `interface filter pushes down to SQL`() = runBlocking {
        store.writeNowForTest(
            listOf(
                ev(1, iface = "android.app.IActivityManager"),
                ev(2, iface = "android.os.IServiceManager"),
            )
        )
        val page = store.query(EventFilter(interfaceContains = "ServiceManager"), null, 50)
        assertEquals(1, page.items.size)
        assertEquals(2L, page.items[0].id)
    }

    @Test
    fun `bucket filter maps to decode source`() = runBlocking {
        store.writeNowForTest(
            listOf(
                ev(1, source = DecodeSource.AIDL_Q),
                ev(2, source = DecodeSource.HIDL_DESCRIPTOR),
            )
        )
        val page = store.query(EventFilter(bucketsAllowed = setOf(CoverageBucket.HIDL)), null, 50)
        assertEquals(1, page.items.size)
        assertEquals(2L, page.items[0].id)
    }

    @Test
    fun `stack module filter matches frame modules only`() = runBlocking {
        store.writeNowForTest(
            listOf(
                ev(1, modules = listOf("libgui.so", "libbinder.so")),
                ev(2, modules = listOf("libc.so")),
                ev(3, modules = emptyList()),   // 无栈:stackModule 非空时不通过
            )
        )
        val page = store.query(EventFilter(stackModuleContains = "libgui"), null, 50)
        assertEquals(1, page.items.size)
        assertEquals(1L, page.items[0].id)
    }

    @Test
    fun `keyset pagination walks newest to oldest without overlap`() = runBlocking {
        store.writeNowForTest((1L..5L).map { ev(it) })
        val p1 = store.query(EventFilter(), null, 2)
        assertEquals(listOf(5L, 4L), p1.items.map { it.id })
        val p2 = store.query(EventFilter(), p1.nextCursor, 2)
        assertEquals(listOf(3L, 2L), p2.items.map { it.id })
        val p3 = store.query(EventFilter(), p2.nextCursor, 2)
        assertEquals(listOf(1L), p3.items.map { it.id })
        assertNull(p3.nextCursor)   // 到底
    }

    @Test
    fun `matched reply hidden but orphan reply and request shown`() = runBlocking {
        store.writeNowForTest(
            listOf(
                ev(1, isReply = false, pairId = 7),   // request → 显示
                ev(2, isReply = true, pairId = 7),     // matched reply → 隐藏
                ev(3, isReply = true, pairId = 0),     // orphan reply → 显示
            )
        )
        val ids = store.query(EventFilter(), null, 50).items.map { it.id }.toSet()
        assertTrue(1L in ids)
        assertTrue(3L in ids)
        assertFalse(2L in ids)
    }

    @Test
    fun `findPair resolves reply and request by pairId`() = runBlocking {
        store.writeNowForTest(
            listOf(
                ev(1, isReply = false, pairId = 7),
                ev(2, isReply = true, pairId = 7),
            )
        )
        assertEquals(2L, store.findPair(7, wantReply = true)!!.id)
        assertEquals(1L, store.findPair(7, wantReply = false)!!.id)
    }
}
