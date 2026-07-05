package com.btrace.viewer.ui.monitor

import com.btrace.viewer.data.EventFilter
import com.btrace.viewer.data.EventRepository
import com.btrace.viewer.parser.CoverageBucket
import com.btrace.viewer.parser.CoverageSnapshot
import com.btrace.viewer.parser.ParcelParser
import com.btrace.viewer.service.MonitoringServiceConnector
import com.btrace.viewer.service.MonitoringSessionController
import com.btrace.viewer.service.MonitoringSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * spec § 过滤器重设计:验证 toggleBucketFilter / updateInterfaceFilter / updateMethodFilter
 * 的状态管理行为。
 *
 * 关键约束:
 * - toggleBucketFilter 立即推到 EventRepository(不再等"应用"按钮)
 * - 文本过滤 debounce 300ms:窗口内多次调用只推一次
 * - clearFilter 取消未触发的 debounce 任务
 *
 * MonitoringServiceConnector.ensureBound() 是 suspend 函数,Mockito 无法在 Kotlin 层直接 stub。
 * 绕过方案:通过 Mockito Java API 的 doAnswer + InvocationOnMock 拿到 Continuation 并立即 resume。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorViewModelFilterTest {

    private val testDispatcher = StandardTestDispatcher()

    private val filterFlow = MutableStateFlow(EventFilter())
    private val coverageFlow = MutableStateFlow(CoverageSnapshot.EMPTY)
    private val filteredEventsFlow = MutableStateFlow(emptyList<com.btrace.viewer.model.BinderEvent>())
    private val eventCountFlow = MutableStateFlow(0)
    private val eventRateFlow = MutableStateFlow(0)

    private lateinit var mockRepo: EventRepository
    private lateinit var mockConnector: MonitoringServiceConnector
    private lateinit var mockParser: ParcelParser
    private lateinit var mockController: MonitoringSessionController
    private lateinit var mockSocket: com.btrace.viewer.data.SocketClient
    private lateinit var viewModel: MonitorViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // mockito-inline 允许 mock final 类。
        mockRepo = mock()
        mockConnector = mock()
        mockParser = mock()
        mockController = mock()
        mockSocket = mock()

        // ── EventRepository stubs ──
        whenever(mockRepo.currentFilter).thenReturn(filterFlow)
        whenever(mockRepo.coverageFlow).thenReturn(coverageFlow)
        whenever(mockRepo.filteredEvents).thenReturn(filteredEventsFlow)
        whenever(mockRepo.eventCount).thenReturn(eventCountFlow)
        whenever(mockRepo.eventRate).thenReturn(eventRateFlow)

        // query 是 suspend:filter 变非空时 init 的 collectLatest 会调它取检索首页。
        // 默认返回空页,避免未 stub 时返回 null 触发 NPE。suspend stub 须在协程上下文注册。
        // cursor 传 null(首页),mockito-kotlin any() 不匹配 null,须用 anyOrNull()。
        kotlinx.coroutines.runBlocking {
            whenever(
                mockRepo.query(
                    any(),                          // filter: 非空
                    org.mockito.kotlin.anyOrNull(), // cursor: 可空,首页传 null
                    any(),                          // pageSize: primitive Int,须 any()
                )
            ).thenReturn(com.btrace.viewer.data.EventPage(emptyList(), null))
        }

        // setFilter 侧效应:同步更新 filterFlow,模拟真实 EventRepository 行为。
        org.mockito.kotlin.doAnswer { inv ->
            filterFlow.value = inv.getArgument<EventFilter>(0)
            Unit
        }.whenever(mockRepo).setFilter(any())

        org.mockito.kotlin.doAnswer {
            filterFlow.value = EventFilter()
            Unit
        }.whenever(mockRepo).clearFilter()

        // ── MonitoringSessionController stubs ──
        whenever(mockController.state).thenReturn(MutableStateFlow(MonitoringSessionState.IDLE))
        whenever(mockController.targetAppName).thenReturn(MutableStateFlow(""))
        whenever(mockController.errorMessage).thenReturn(MutableStateFlow(null))

        // ── MonitoringServiceConnector.ensureBound() suspend stub ──
        // ensureBound() 在 JVM 字节码层是: ensureBound(Continuation<MonitoringSessionController>): Object
        // 用 Mockito doAnswer 挂钩:拿到 Continuation 参数,立即 resume(mockController)。
        // 必须通过 Java 反射调用 when-proxy 上的方法来注册 stub,因为 Kotlin 编译器拒绝在
        // non-suspend 上下文直接调用 suspend fun。
        val whenProxy = org.mockito.Mockito.doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            // arguments 在真实调用时只有 1 个元素(Continuation),
            // 在 stub 注册触发的"空调用"时 arguments 可能为空,这里做防御。
            val last = invocation.arguments.lastOrNull()
            if (last is kotlin.coroutines.Continuation<*>) {
                @Suppress("UNCHECKED_CAST")
                (last as kotlin.coroutines.Continuation<MonitoringSessionController>).resume(mockController)
            }
            kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
        }.`when`(mockConnector)
        // 在 when-proxy 上通过反射调用 ensureBound,完成 stub 注册。
        MonitoringServiceConnector::class.java
            .getDeclaredMethod("ensureBound", kotlin.coroutines.Continuation::class.java)
            .invoke(whenProxy, null)

        viewModel = MonitorViewModel(
            eventRepository = mockRepo,
            parcelParser = mockParser,
            connector = mockConnector,
            socketClient = mockSocket
        )
        // selectEvent 默认走 Dispatchers.Default,testDispatcher 无法控制其调度;
        // 注入 testDispatcher 让 advanceUntilIdle 能驱动协程跑完。
        viewModel.setSelectDispatcherForTest(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // toggleBucketFilter 立即推到 EventRepository
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `toggleBucketFilter immediately calls setFilter on EventRepository`() = runTest {
        advanceUntilIdle()

        viewModel.toggleBucketFilter(CoverageBucket.AIDL)
        advanceUntilIdle()

        val captor = argumentCaptor<EventFilter>()
        verify(mockRepo, times(1)).setFilter(captor.capture())
        val expected = EventFilter.ALL_BUCKETS - CoverageBucket.AIDL
        assertEquals(expected, captor.firstValue.bucketsAllowed)
    }

    @Test
    fun `toggleBucketFilter toggling same bucket twice restores full set`() = runTest {
        advanceUntilIdle()

        viewModel.toggleBucketFilter(CoverageBucket.AIDL)
        advanceUntilIdle()
        viewModel.toggleBucketFilter(CoverageBucket.AIDL)
        advanceUntilIdle()

        val captor = argumentCaptor<EventFilter>()
        verify(mockRepo, times(2)).setFilter(captor.capture())
        assertTrue(captor.secondValue.bucketsAllowed.contains(CoverageBucket.AIDL))
    }

    @Test
    fun `toggleBucketFilter last bucket falls back to ALL_BUCKETS`() = runTest {
        advanceUntilIdle()

        EventFilter.ALL_BUCKETS.filter { it != CoverageBucket.AIDL }.forEach {
            viewModel.toggleBucketFilter(it)
            advanceUntilIdle()
        }
        viewModel.toggleBucketFilter(CoverageBucket.AIDL)
        advanceUntilIdle()

        val captor = argumentCaptor<EventFilter>()
        verify(mockRepo, times(EventFilter.ALL_BUCKETS.size)).setFilter(captor.capture())
        assertEquals(EventFilter.ALL_BUCKETS, captor.lastValue.bucketsAllowed)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // updateInterfaceFilter debounce 300ms
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `updateInterfaceFilter - rapid calls within 300ms result in single setFilter`() = runTest {
        advanceUntilIdle()

        viewModel.updateInterfaceFilter("I")
        advanceTimeBy(100)
        viewModel.updateInterfaceFilter("IP")
        advanceTimeBy(100)
        viewModel.updateInterfaceFilter("IPa")
        // 总 200ms < 300ms,尚未触发
        verify(mockRepo, never()).setFilter(any())

        advanceTimeBy(MonitorViewModel.FILTER_DEBOUNCE_MS)
        advanceUntilIdle()

        val captor = argumentCaptor<EventFilter>()
        verify(mockRepo, times(1)).setFilter(captor.capture())
        assertEquals("IPa", captor.firstValue.interfaceContains)
    }

    @Test
    fun `updateInterfaceFilter fires after debounce window`() = runTest {
        advanceUntilIdle()

        viewModel.updateInterfaceFilter("IPackageManager")
        advanceTimeBy(MonitorViewModel.FILTER_DEBOUNCE_MS)
        advanceUntilIdle()

        val captor = argumentCaptor<EventFilter>()
        verify(mockRepo, times(1)).setFilter(captor.capture())
        assertEquals("IPackageManager", captor.firstValue.interfaceContains)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // updateMethodFilter debounce 300ms
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `updateMethodFilter - rapid calls collapsed to one`() = runTest {
        advanceUntilIdle()

        viewModel.updateMethodFilter("s")
        advanceTimeBy(50)
        viewModel.updateMethodFilter("st")
        advanceTimeBy(50)
        viewModel.updateMethodFilter("startActivity")

        verify(mockRepo, never()).setFilter(any())

        advanceTimeBy(MonitorViewModel.FILTER_DEBOUNCE_MS)
        advanceUntilIdle()

        val captor = argumentCaptor<EventFilter>()
        verify(mockRepo, times(1)).setFilter(captor.capture())
        assertEquals("startActivity", captor.firstValue.methodContains)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // clearFilter 取消 debounce
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `clearFilter cancels pending interface debounce and calls repo clearFilter`() = runTest {
        advanceUntilIdle()

        viewModel.updateInterfaceFilter("IPm")
        advanceTimeBy(100)

        viewModel.clearFilter()
        advanceUntilIdle()

        verify(mockRepo, never()).setFilter(any())
        verify(mockRepo, times(1)).clearFilter()
    }

    @Test
    fun `clearFilter cancels pending method debounce`() = runTest {
        advanceUntilIdle()

        viewModel.updateMethodFilter("startAct")
        advanceTimeBy(150)

        viewModel.clearFilter()
        advanceUntilIdle()

        verify(mockRepo, never()).setFilter(any())
        verify(mockRepo, times(1)).clearFilter()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // updateStackModuleFilter debounce + clearFilter cancellation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `updateStackModuleFilter fires after debounce window`() = runTest {
        advanceUntilIdle()

        viewModel.updateStackModuleFilter("libgui")
        advanceTimeBy(MonitorViewModel.FILTER_DEBOUNCE_MS)
        advanceUntilIdle()

        val captor = argumentCaptor<EventFilter>()
        verify(mockRepo, times(1)).setFilter(captor.capture())
        assertEquals("libgui", captor.firstValue.stackModuleContains)
    }

    @Test
    fun `clearFilter cancels pending stack module debounce`() = runTest {
        advanceUntilIdle()

        viewModel.updateStackModuleFilter("libgui")
        advanceTimeBy(150)

        viewModel.clearFilter()
        advanceUntilIdle()

        verify(mockRepo, never()).setFilter(any())
        verify(mockRepo, times(1)).clearFilter()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // showFilterDialog 不再调用 setFilter
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `showFilterDialog sets flag without calling setFilter`() = runTest {
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showFilterDialog)
        viewModel.showFilterDialog()
        assertTrue(viewModel.uiState.value.showFilterDialog)

        verify(mockRepo, never()).setFilter(any())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // spec 2026-05-09 § 3:selectEvent 串接 linkedReply
    //
    // 由于 daemon 当前 BPF sender_uid 过滤策略导致 reply 帧不在监控范围内被采集,
    // 真机端到端无法触发 LinkedReplySection 的"已配对"分支。这两个 ViewModel 单测
    // 直接覆盖 selectEvent 的填充逻辑,弥补这部分回归覆盖。
    // ─────────────────────────────────────────────────────────────────────────

    private fun newEvent(
        id: Long,
        isReply: Boolean,
        pairId: Long,
    ): com.btrace.viewer.model.BinderEvent = com.btrace.viewer.model.BinderEvent(
        id = id,
        timestamp = id * 1_000_000L,
        pid = 1,
        uid = 1000,
        code = 1,
        flags = 0,
        rawParcel = ByteArray(0),
        isReply = isReply,
        pairId = pairId,
    )

    @Test
    fun `selectEvent fills linkedReply when matching reply exists`() = runTest {
        advanceUntilIdle()

        val request = newEvent(id = 1, isReply = false, pairId = 42L)
        val reply = newEvent(id = 2, isReply = true, pairId = 42L)
        whenever(mockRepo.getEvent(1L)).thenReturn(request)
        whenever(mockRepo.findReplyForRequest(request)).thenReturn(reply)
        whenever(mockParser.formatHex(any(), any())).thenReturn("DEADBEEF")

        viewModel.selectEvent(1L)
        advanceUntilIdle()

        val detail = viewModel.selectedEvent.value
        assertTrue("EventDetail 应已写入", detail != null)
        assertEquals(request, detail!!.event)
        assertEquals(reply, detail.linkedReply)
        // linkedReplyHex 是 lazy:读取时才触发 formatHex(reply.rawParcel)
        assertEquals("DEADBEEF", detail.linkedReplyHex)
    }

    @Test
    fun `selectEvent leaves linkedReply null when no reply paired`() = runTest {
        advanceUntilIdle()

        val request = newEvent(id = 1, isReply = false, pairId = 42L)
        whenever(mockRepo.getEvent(1L)).thenReturn(request)
        whenever(mockRepo.findReplyForRequest(request)).thenReturn(null)
        whenever(mockParser.formatHex(any(), any())).thenReturn("CAFE")

        viewModel.selectEvent(1L)
        advanceUntilIdle()

        val detail = viewModel.selectedEvent.value
        assertTrue("EventDetail 应已写入", detail != null)
        assertEquals(request, detail!!.event)
        assertEquals(null, detail.linkedReply)
        assertEquals(null, detail.linkedReplyHex)
    }

    @Test
    fun `selectEvent does not call formatHex for reply when user does not read hex`() = runTest {
        // 验证 lazy 行为:linkedReplyHex 不被读取时,reply formatHex 不应被调用,
        // 避免 reply parcel 几百 KB hex 化的浪费。
        advanceUntilIdle()

        val request = newEvent(id = 1, isReply = false, pairId = 42L)
        val reply = newEvent(id = 2, isReply = true, pairId = 42L)
        whenever(mockRepo.getEvent(1L)).thenReturn(request)
        whenever(mockRepo.findReplyForRequest(request)).thenReturn(reply)
        whenever(mockParser.formatHex(any(), any())).thenReturn("HEX")

        viewModel.selectEvent(1L)
        advanceUntilIdle()

        val detail = viewModel.selectedEvent.value!!
        // 此时只读了 request 的 hexDump (selectEvent 急切构造);reply hex 还没被访问
        // → formatHex 应该只调用了 1 次(request 那次)。
        verify(mockParser, times(1)).formatHex(any(), any())

        // 主动访问 linkedReplyHex 触发 lazy
        val replyHex = detail.linkedReplyHex
        assertEquals("HEX", replyHex)
        verify(mockParser, times(2)).formatHex(any(), any())
    }
}
