package com.btrace.viewer.ui.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.btrace.viewer.data.EventFilter
import com.btrace.viewer.data.EventRepository
import com.btrace.viewer.data.SocketClient
import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.parser.CoverageBucket
import com.btrace.viewer.parser.CoverageSnapshot
import com.btrace.viewer.parser.ParcelParser
import com.btrace.viewer.service.MonitoringServiceConnector
import com.btrace.viewer.service.MonitoringSessionController
import com.btrace.viewer.service.MonitoringSessionState
import com.btrace.viewer.utils.CLogUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI 用的"粗粒度"状态,与 controller 的 7 态有显式映射(下面 mapToUi)。 */
enum class MonitoringState { IDLE, MONITORING, PAUSED, STOPPING }

data class MonitorUiState(
    val monitoringState: MonitoringState = MonitoringState.IDLE,
    val targetAppName: String = "",
    val showFilterDialog: Boolean = false,
    val errorMessage: String? = null,
    val sessionState: MonitoringSessionState = MonitoringSessionState.IDLE
)

@HiltViewModel
class MonitorViewModel @Inject constructor(
    private val eventRepository: EventRepository,
    private val parcelParser: ParcelParser,
    private val connector: MonitoringServiceConnector,
    private val socketClient: SocketClient
) : ViewModel() {

    /**
     * selectEvent 后台计算 hex / 反查 reply 用的 dispatcher。生产路径 Default(默认),
     * 单元测试通过 setSelectDispatcherForTest 注入 TestDispatcher 让 advanceUntilIdle
     * 能驱动协程跑完。@VisibleForTesting 让 Lint 在生产代码引用时报错。
     */
    @Volatile
    private var selectDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default

    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.NONE)
    internal fun setSelectDispatcherForTest(dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        selectDispatcher = dispatcher
    }

    companion object {
        private const val TAG = "MonitorViewModel"
        /** 文本框 debounce 窗口,ms。300ms 内多次输入合并为一次推送到 EventRepository。 */
        const val FILTER_DEBOUNCE_MS = 300L
    }

    /**
     * 详情页 ViewModel 视图。
     *
     * - [event]:用户点击的那条事件本身
     * - [hexDump]:event.rawParcel 的 hex dump(供原 Parcel 数据区显示)。
     *   request parcel 通常 < 1KB,急切构造影响小。
     * - [linkedReply]:仅当 event 是 request 且 pairId 配对到 reply 时非空。
     *   reply 自身详情页 / 无配对 / 还没收到 reply 时一律为 null —— UI 据此切换
     *   渲染分支,不在 ViewModel 里塞展示文案。
     * - [linkedReplyHex]:linkedReply.rawParcel 的完整 hex dump,**lazy** ——
     *   spec § 8.2 reply 隐私默认折叠,绝大多数用户不会展开;reply parcel 可达
     *   数百 KB,hex 化 O(N) 字符串构造一旦预先做了就常驻 EventDetail 内存。
     *   因此用 lazy(PUBLICATION) 推迟到 UI 真要展开才算。linkedReply == null 时
     *   读出来也是 null。
     *
     * 这里特意不用 data class —— [linkedReplyHex] 是 lazy 计算结果,默认的
     * data class equals/hashCode 会触发 lazy 计算,违背 lazy 初衷。需要 equals
     * 时按 [event].id 即可。
     */
    class EventDetail internal constructor(
        val event: BinderEvent,
        val hexDump: String,
        val linkedReply: BinderEvent?,
        private val linkedReplyHexProvider: (() -> String)?,
        val linkedRequest: BinderEvent?,
        private val linkedRequestHexProvider: (() -> String)?,
    ) {
        val linkedReplyHex: String? by lazy(LazyThreadSafetyMode.PUBLICATION) {
            linkedReplyHexProvider?.invoke()
        }
        // reply 详情页里「请求数据」section 的 hex,与 linkedReplyHex 对称走 lazy:
        // 大多数用户不展开请求 hex,推迟字符串构造。
        val linkedRequestHex: String? by lazy(LazyThreadSafetyMode.PUBLICATION) {
            linkedRequestHexProvider?.invoke()
        }

        // 仅按 event.id 比较 —— 详情页打开同一条事件时 StateFlow 能复用现 EventDetail,
        // 避免 lazy 重新算。其它字段(hexDump / linkedReply / linkedRequest)由 event.id
        // 唯一确定(event.id 决定 isReply 决定走哪条 lookup,buffer 内容随时间变化但同一
        // 时刻的 event.id 对应固定 detail)。
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EventDetail) return false
            return event.id == other.event.id &&
                (linkedReply?.id) == (other.linkedReply?.id) &&
                (linkedRequest?.id) == (other.linkedRequest?.id)
        }
        override fun hashCode(): Int {
            var h = event.id.hashCode()
            h = h * 31 + (linkedReply?.id?.hashCode() ?: 0)
            h = h * 31 + (linkedRequest?.id?.hashCode() ?: 0)
            return h
        }
    }

    private val _uiState = MutableStateFlow(MonitorUiState())
    val uiState: StateFlow<MonitorUiState> = _uiState.asStateFlow()

    private val _selectedEvent = MutableStateFlow<EventDetail?>(null)
    val selectedEvent: StateFlow<EventDetail?> = _selectedEvent.asStateFlow()

    // 检索分页状态(仅有过滤时启用)。pageCursor/pageEndReached/loadingPage 只在主线程协程读写。
    private val _pagedEvents = MutableStateFlow<List<BinderEvent>>(emptyList())
    private var pageCursor: Long? = null
    private var pageEndReached = false
    private var loadingPage = false

    // 列表数据源:无过滤 → 实时内存窗口(最新在底,跟随尾部);有过滤 → 全量 DB 分页
    // (最新匹配在顶,下滚 loadMore 加载更旧历史)。同一个列表,数据源在此内部切换,UI 不感知。
    @OptIn(ExperimentalCoroutinesApi::class)
    val displayEvents: StateFlow<List<BinderEvent>> =
        eventRepository.currentFilter.flatMapLatest { f ->
            if (f.isEmpty()) eventRepository.filteredEvents else _pagedEvents
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val eventCount = eventRepository.eventCount.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )
    val eventRate = eventRepository.eventRate.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )
    val currentFilter = eventRepository.currentFilter.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), EventFilter()
    )
    // spec § 6.5:覆盖率仪表盘的源 StateFlow。EventRepository 在 emit tick 里推一次,
    // UI 顶部卡片直接 collectAsState。
    val coverage = eventRepository.coverageFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), CoverageSnapshot.EMPTY
    )

    // debounce Job:持有上一次文本输入的延迟推送任务,新输入到来时取消旧任务再启新任务。
    private var interfaceDebounceJob: Job? = null
    private var methodDebounceJob: Job? = null
    private var stackModuleDebounceJob: Job? = null

    init {
        CLogUtils.d(TAG, "MonitorViewModel 初始化")
        viewModelScope.launch {
            val controller = connector.ensureBound()
            observeController(controller)
        }
        // 过滤变化时重建检索分页首页(全量 DB 查);无过滤则清空,列表 flatMapLatest 切回实时窗口。
        viewModelScope.launch {
            eventRepository.currentFilter.collectLatest { f ->
                if (f.isEmpty()) {
                    _pagedEvents.value = emptyList()
                    pageCursor = null
                    pageEndReached = false
                } else {
                    val page = eventRepository.query(f, cursor = null, pageSize = EventRepository.DEFAULT_PAGE_SIZE)
                    _pagedEvents.value = page.items
                    pageCursor = page.nextCursor
                    pageEndReached = page.nextCursor == null
                }
            }
        }
    }

    private fun observeController(controller: MonitoringSessionController) {
        viewModelScope.launch {
            controller.state.collect { s ->
                _uiState.value = _uiState.value.copy(
                    monitoringState = mapToUi(s),
                    sessionState = s
                )
            }
        }
        viewModelScope.launch {
            controller.targetAppName.collect { name ->
                _uiState.value = _uiState.value.copy(targetAppName = name)
            }
        }
        viewModelScope.launch {
            controller.errorMessage.collect { msg ->
                if (msg != null) _uiState.value = _uiState.value.copy(errorMessage = msg)
            }
        }
    }

    private fun mapToUi(s: MonitoringSessionState): MonitoringState = when (s) {
        MonitoringSessionState.IDLE, MonitoringSessionState.FAILED -> MonitoringState.IDLE
        MonitoringSessionState.STARTING,
        MonitoringSessionState.HANDSHAKING,
        MonitoringSessionState.CONNECTED,
        MonitoringSessionState.RECONNECTING -> MonitoringState.MONITORING
        MonitoringSessionState.PAUSED -> MonitoringState.PAUSED
        MonitoringSessionState.STOPPING -> MonitoringState.STOPPING
    }

    fun pauseMonitoring() {
        viewModelScope.launch { connector.ensureBound().pause() }
    }

    fun resumeMonitoring() {
        viewModelScope.launch { connector.ensureBound().resume() }
    }

    fun stopMonitoring() {
        viewModelScope.launch {
            connector.ensureBound().stop()
            connector.requestStop()
        }
    }

    fun showFilterDialog() {
        _uiState.value = _uiState.value.copy(showFilterDialog = true)
    }

    fun hideFilterDialog() { _uiState.value = _uiState.value.copy(showFilterDialog = false) }

    /**
     * 文本输入 debounce 300ms 后推到 EventRepository。
     * 前一个未触发的延迟任务会被 cancel,避免高速输入触发多次过滤刷新。
     */
    fun updateInterfaceFilter(v: String) {
        interfaceDebounceJob?.cancel()
        interfaceDebounceJob = viewModelScope.launch {
            delay(FILTER_DEBOUNCE_MS)
            eventRepository.setFilter(eventRepository.currentFilter.value.copy(interfaceContains = v))
        }
    }

    fun updateMethodFilter(v: String) {
        methodDebounceJob?.cancel()
        methodDebounceJob = viewModelScope.launch {
            delay(FILTER_DEBOUNCE_MS)
            eventRepository.setFilter(eventRepository.currentFilter.value.copy(methodContains = v))
        }
    }

    fun updateStackModuleFilter(v: String) {
        stackModuleDebounceJob?.cancel()
        stackModuleDebounceJob = viewModelScope.launch {
            delay(FILTER_DEBOUNCE_MS)
            eventRepository.setFilter(eventRepository.currentFilter.value.copy(stackModuleContains = v))
        }
    }

    /**
     * 切换某个桶的勾选状态。立即推到 EventRepository,无需点"应用"按钮。
     * 为避免出现"全部取消 = 0 桶可见"的死锁,最后一个被取消时回退到全选。
     *
     * 读取源用 [eventRepository].currentFilter.value(而非 stateIn 的缓存),
     * 保证在 WhileSubscribed 无订阅者时也能看到最新值。
     */
    fun toggleBucketFilter(bucket: CoverageBucket) {
        val latest = eventRepository.currentFilter.value
        val current = latest.bucketsAllowed
            .let { if (it.isEmpty()) EventFilter.ALL_BUCKETS else it }
        val next = if (current.contains(bucket)) current - bucket else current + bucket
        val resolved = if (next.isEmpty()) EventFilter.ALL_BUCKETS else next
        eventRepository.setFilter(latest.copy(bucketsAllowed = resolved))
    }

    fun clearFilter() {
        // 取消未触发的 debounce 任务,避免 clearFilter 后紧接着 debounce 再次写入脏值。
        interfaceDebounceJob?.cancel()
        methodDebounceJob?.cancel()
        stackModuleDebounceJob?.cancel()
        eventRepository.clearFilter()
    }

    fun clearEvents() { eventRepository.clearEvents() }

    /**
     * 检索模式(有过滤)滑到底:加载下一页更旧的匹配 append 到列表尾。
     * 无过滤 / 已到底 / 正在加载时空操作(幂等,可被滚动重复触发)。
     */
    fun loadMore() {
        val f = eventRepository.currentFilter.value
        if (f.isEmpty() || pageEndReached || loadingPage) return
        val cursor = pageCursor ?: return
        loadingPage = true
        viewModelScope.launch {
            val page = eventRepository.query(f, cursor, EventRepository.DEFAULT_PAGE_SIZE)
            _pagedEvents.value = _pagedEvents.value + page.items
            pageCursor = page.nextCursor
            pageEndReached = page.nextCursor == null
            loadingPage = false
        }
    }

    fun clearErrorMessage() { _uiState.value = _uiState.value.copy(errorMessage = null) }

    fun selectEvent(eventId: Long) {
        viewModelScope.launch(selectDispatcher) {
            val e = eventRepository.getEvent(eventId) ?: return@launch
            val hex = parcelParser.formatHex(e.rawParcel)
            // spec 2026-05-09 § 3:request 详情页串接对应 reply,reply 详情页反向串接
            // 配对 request。无配对 / buffer 已淘汰 / 还没收到对端时,linked* == null,
            // UI 渲染走"未配对/等待"分支。
            val reply = eventRepository.findReplyForRequest(e)
            val request = eventRepository.findRequestForReply(e)
            // hex 走 lazy:绝大多数用户不展开链接事件 hex(默认折叠),只把 ByteArray
            // 与 parser 引用进闭包,真展开时才 formatHex。
            val replyHexProvider: (() -> String)? = reply?.let { r ->
                { parcelParser.formatHex(r.rawParcel) }
            }
            val requestHexProvider: (() -> String)? = request?.let { r ->
                { parcelParser.formatHex(r.rawParcel) }
            }
            _selectedEvent.value = EventDetail(
                event = e,
                hexDump = hex,
                linkedReply = reply,
                linkedReplyHexProvider = replyHexProvider,
                linkedRequest = request,
                linkedRequestHexProvider = requestHexProvider,
            )
        }
    }

    fun dismissDetail() { _selectedEvent.value = null }
}
