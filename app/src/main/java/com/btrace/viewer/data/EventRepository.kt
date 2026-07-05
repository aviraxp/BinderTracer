package com.btrace.viewer.data

import androidx.annotation.VisibleForTesting
import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.model.Direction
import com.btrace.viewer.parser.CoverageBucket
import com.btrace.viewer.parser.CoverageSnapshot
import com.btrace.viewer.parser.CoverageStats
import com.btrace.viewer.parser.InterfaceIndex
import com.btrace.viewer.parser.MethodResolver
import com.btrace.viewer.parser.ParcelArgumentDecoder
import com.btrace.viewer.parser.ParcelParser
import com.btrace.viewer.parser.decoders.DecodeSource
import com.btrace.viewer.utils.CLogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 事件过滤条件。
 *
 * - [interfaceContains] / [methodContains]:子串匹配,空串表示该维度不参与。
 * - [bucketsAllowed]:5 桶白名单,事件按 [CoverageBucket.of] 聚合后落在集合内才通过;
 *   默认全 5 桶 = 不过滤。空集合等同于全选(避免 UI 异常清空导致零事件可见)。
 */
data class EventFilter(
    val interfaceContains: String = "",
    val methodContains: String = "",
    val bucketsAllowed: Set<CoverageBucket> = ALL_BUCKETS,
    /**
     * 调用栈中出现过的 .so 路径子串(忽略大小写)。空串 = 该维度不参与。
     *
     * 典型用法:输入 `libgui` 看哪些 binder 调用经过了 libgui.so。
     *
     * 语义:对事件的 stackTrace.uFrames + kFrames 的 module 字段做子串匹配,任一帧命中即通过。
     * **该字段非空时**,stackTrace==null 的事件一律不通过(没栈无法验证)。
     */
    val stackModuleContains: String = ""
) {
    fun isEmpty(): Boolean =
        interfaceContains.isBlank() &&
            methodContains.isBlank() &&
            stackModuleContains.isBlank() &&
            (bucketsAllowed.isEmpty() || bucketsAllowed.size == ALL_BUCKETS.size)

    fun matches(event: BinderEvent): Boolean {
        if (isEmpty()) return true
        val interfaceMatch = interfaceContains.isBlank() ||
            event.interfaceName.contains(interfaceContains, ignoreCase = true)
        val methodMatch = methodContains.isBlank() ||
            event.methodName.contains(methodContains, ignoreCase = true)
        val bucketMatch = bucketsAllowed.isEmpty() ||
            bucketsAllowed.size == ALL_BUCKETS.size ||
            bucketsAllowed.contains(bucketOf(event))
        val stackModuleMatch = stackModuleContains.isBlank() || run {
            val st = event.stackTrace ?: return@run false
            st.uFrames.any { it.module.contains(stackModuleContains, ignoreCase = true) } ||
                st.kFrames.any { it.module.contains(stackModuleContains, ignoreCase = true) }
        }
        return interfaceMatch && methodMatch && bucketMatch && stackModuleMatch
    }

    companion object {
        val ALL_BUCKETS: Set<CoverageBucket> = CoverageBucket.values().toSet()

        fun bucketOf(event: BinderEvent): CoverageBucket =
            event.decodeSource?.let { CoverageBucket.of(it) } ?: CoverageBucket.UNKNOWN
    }
}

/**
 * 事件数据仓库 —— 全量事件的单一事实来源(SQLite,[ColdEventStore])+ 内存滑动窗口缓存。
 *
 * 冷热分层对上层透明(见 docs/事件缓存-不限量重构方案.md):
 *   - 写:每条事件全量落 [ColdEventStore] + 进固定大小内存窗口([BoundedEventBuffer]);
 *     窗口淘汰≠事件消失(DB 已持有),不再有"限量 / 堆压淘汰"概念。
 *   - 读:实时列表走内存窗口(高频刷新);检索/分页([query])、详情/配对点查穿透 DB。
 *
 * 性能特性:
 *   - UI 订阅侧走 tick 合并:写入只置 [dirty],一个后台协程以 [EMIT_INTERVAL_MS]
 *     的周期吞掉中间态,合并发射一次快照。1000+ events/s 下 Compose 重组频率被钳在 ≤25Hz。
 *   - 热路径(addEvent/parseEvent)无 verbose 日志,避免字符串模板分配。
 */
@Singleton
class EventRepository @Inject constructor(
    private val parcelParser: ParcelParser,
    private val methodResolver: MethodResolver,
    private val argumentDecoder: ParcelArgumentDecoder,
    private val appRepository: AppRepository,
    private val interfaceIndex: InterfaceIndex,
    private val serviceManagerCatalog: com.btrace.viewer.parser.ServiceManagerCatalog,
    // 全量事件落盘的单一事实来源。默认 null = 禁用:JVM 单测直接 new 时不碰 SQLite;
    // 生产由 DI 注入实例。null 时退化为纯内存窗口(无检索穿透)。
    private val coldStore: ColdEventStore? = null,
) {
    companion object {
        private const val TAG = "EventRepository"

        /**
         * 内存滑动窗口大小。全量事件落 [ColdEventStore],内存只保留最近这么多条供实时列表高频刷新;
         * 淘汰出窗口的事件已在 DB 里,不算数据丢失。不再是用户可配上限(见 docs 重构方案 §10)。
         */
        const val WINDOW_SIZE = 5000

        private const val EMIT_INTERVAL_MS = 40L

        /** 检索默认页大小(键集游标分页,见 [query])。 */
        const val DEFAULT_PAGE_SIZE = 100
    }

    // lock 保护 rate 计数器、_eventCount、totalCount 的同步;窗口与 id 索引由 buffer 自身负责。
    private val lock = Any()

    // spec § 6.5:覆盖率统计。全量落盘后 coverage 是**累计**语义(onEventAdded 增,窗口淘汰不减,
    // clearEvents 归零),反映本会话全量事件的覆盖分布,而非内存窗口内的分布。
    private val coverageStats = CoverageStats()

    // 固定大小内存滑动窗口(实时列表用)。全量已落 DB,窗口淘汰不再落盘 / 不减 coverage,故不挂 onEvicted。
    private val buffer = BoundedEventBuffer(WINDOW_SIZE)

    // 本会话累计事件数(全量,含已滑出窗口的)。lock 内读写。
    private var totalCount = 0

    private val _coverage = MutableStateFlow(CoverageSnapshot.EMPTY)
    val coverageFlow: StateFlow<CoverageSnapshot> = _coverage.asStateFlow()

    private val _filteredEvents = MutableStateFlow<List<BinderEvent>>(emptyList())
    val filteredEvents: StateFlow<List<BinderEvent>> = _filteredEvents.asStateFlow()

    private val _currentFilter = MutableStateFlow(EventFilter())
    val currentFilter: StateFlow<EventFilter> = _currentFilter.asStateFlow()

    private val _eventCount = MutableStateFlow(0)
    val eventCount: StateFlow<Int> = _eventCount.asStateFlow()

    private var lastSecondCount = 0
    private var lastSecondTime = System.currentTimeMillis()
    private val _eventRate = MutableStateFlow(0)
    val eventRate: StateFlow<Int> = _eventRate.asStateFlow()

    // 写端置位,tick 协程吞掉并发射一次快照。
    private val dirty = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // spec § 6.5:当前监控目标的 uid,由 MonitorViewModel.startMonitoring 灌入。
    // <= 0 时 Direction.infer 返回 UNKNOWN,UI 退化为不分方向。
    @Volatile
    private var targetUid: Int = 0

    // spec 2026-05-03 § 3.2:被监控 App 的包名(由 setTargetUid 反查并 cache)。
    // parseEvent 计算 resolveCandidates 时优先级 1。null = 未启动监控 / 反查失败。
    @Volatile
    private var targetPackage: String? = null

    init {
        CLogUtils.d(TAG, "EventRepository 初始化, capacity=${buffer.capacity()}, emit tick=${EMIT_INTERVAL_MS}ms")
        scope.launch {
            while (isActive) {
                delay(EMIT_INTERVAL_MS)
                if (dirty.compareAndSet(true, false)) {
                    emitSnapshot()
                }
            }
        }
    }

    /**
     * MonitorViewModel.startMonitoring 时灌入当前监控目标 uid,用于 [parseEvent] 推断 direction。
     * 不传或 uid <= 0 时所有事件 direction 为 UNKNOWN(UI 退化为不分方向)。
     */
    fun setTargetUid(uid: Int) {
        targetUid = uid
        // spec 2026-05-03 § 3.2:在 setTargetUid 时反查包名一次性 cache,避免每事件都查 PM。
        // uid <= 0 → 清空(MonitorViewModel.stopMonitoring 时灌 0)。
        targetPackage = if (uid > 0) appRepository.getPackageNameForUid(uid) else null
        // 同步给 ParcelParser → TransactionPairer:配对方向判定要用同一个 targetUid。
        // EventRepository 与 ParcelParser 都 @Singleton,这里只在切换监控目标时被调一次。
        parcelParser.setCurrentTargetUid(uid)
        CLogUtils.d(TAG, "setTargetUid() targetUid=$uid targetPackage=$targetPackage")
    }

    /**
     * 全量检索一页(过滤条件下推 SQL,键集游标分页)。实时列表走内存窗口([filteredEvents]),
     * 这里是"停下来全量翻查"的入口:cursor=null 取最新一页,返回 nextCursor=null 表示到底。
     * coldStore 未注入(纯内存单测)时返回空页。
     */
    suspend fun query(
        filter: EventFilter = _currentFilter.value,
        cursor: Long? = null,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): EventPage = coldStore?.query(filter, cursor, pageSize) ?: EventPage(emptyList(), null)

    /**
     * 添加新事件。调用方保证单协程串行(当前为 MonitorViewModel 的 collect 协程)。
     *
     * 解析(含反射、包名查询)放在锁外,与 emit 协程构建快照可并行 —— 解析只写
     * event 自身的懒加载字段,不碰共享容器。
     */
    fun addEvent(event: BinderEvent) {
        parseEvent(event)

        // 全量落盘:每条事件都归档到 DB(offer 非阻塞入队,线程安全,放锁外不拖慢热路径)。
        coldStore?.offer(event)

        // BoundedEventBuffer 不是线程安全的,由调用方串行化。与 emitSnapshot / clearEvents /
        // getEvent 等共用同一把 lock。
        synchronized(lock) {
            coverageStats.onEventAdded(event)   // 累计,窗口淘汰不再递减
            buffer.add(event)                    // 进内存窗口,满则丢最老(已落 DB)
            _eventCount.value = ++totalCount     // 全量累计,非窗口 size
            val now = System.currentTimeMillis()
            lastSecondCount++
            if (now - lastSecondTime >= 1000) {
                _eventRate.value = lastSecondCount
                lastSecondCount = 0
                lastSecondTime = now
            }
        }

        dirty.set(true)
    }

    /**
     * 流程:先反查 callerPackage(用于后续把目标 App 自己的 ClassLoader 喂给 MethodResolver),
     * 再按当前 targetUid 推断 direction(spec § 6.5),
     * 然后统一走 [ParcelParser.decodePipeline] 8 档探测器(spec § 6.3.1),
     * 按命中的 [DecodeSource] 分流:
     *   - REPLY/SPECIAL/HIDL/RAW_ASCII/TARGET_REF:无 AIDL 参数语义,只用 methodHint
     *   - AIDL_Q/P/O:走 MethodResolver + ParcelArgumentDecoder 解参数值
     *
     * 入向调用(INCOMING_REQUEST)的 callerPackage 仍按 sender uid 反查 ——
     * 此时 sender 是外部调用方,callerPackage 表达的就是"是谁在调我",符合 UI 语义。
     */
    private fun parseEvent(event: BinderEvent) {
        val senderPkg = appRepository.getPackageNameForUid(event.uid)
        if (senderPkg != null) {
            event.callerPackage = senderPkg
        }
        // request 帧带 toUid 时,反查目标进程的可读名(系统 uid 走助记名,app uid 走包名)。
        // reply 帧 toUid 通常不带(server→client 方向),反查跳过避免误标。
        if (!event.isReply && event.toUid != 0) {
            event.toPackage = appRepository.getPackageOrSystemNameForUid(event.toUid)
        }

        event.direction = Direction.infer(
            senderUid = event.uid,
            toUid = event.toUid,
            isReply = event.isReply,
            targetUid = targetUid
        )

        val result = parcelParser.decodePipeline(event)
        event.interfaceName = result.interfaceName
        event.decodeSource = result.source
        event.confidence = result.confidence

        // spec 2026-05-03 § 3.2:候选包优先级 = targetPackage → InterfaceIndex.lookupOrdered → senderPkg。
        // 顺序去重(LinkedHashSet),空字符串 / null 跳过。
        event.resolveCandidates = computeResolveCandidates(senderPkg, result.interfaceName)

        when (result.source) {
            DecodeSource.REPLY,
            DecodeSource.SPECIAL_CODE,
            DecodeSource.HIDL_DESCRIPTOR,
            DecodeSource.RAW_ASCII,
            DecodeSource.TARGET_REF -> {
                // 这些档要么已经给了 methodHint(REPLY/SPECIAL/TARGET_REF),
                // 要么没有 AIDL 参数语义。methodHint 不为空就用,否则用 "code=N" 兜底。
                event.methodName = result.methodHint ?: "code=${event.code}"
                // TARGET_REF 兜底升级:用 ServiceManagerCatalog 的全 service 反射表按 code +
                // toPackage 反查候选 service / method。命中则把 interfaceName 换成
                // descriptor,methodName 换成反射出的真实名;miss 保持 raw target@0xN。
                if (result.source == DecodeSource.TARGET_REF) {
                    val candidate = serviceManagerCatalog.lookupByCode(event.code, event.toPackage)
                    if (candidate != null) {
                        event.interfaceName = candidate.descriptor
                        event.methodName = candidate.methodName
                    }
                }
            }
            DecodeSource.AIDL_Q,
            DecodeSource.AIDL_P,
            DecodeSource.AIDL_O -> {
                // AIDL 路径:走 MethodResolver 拿方法签名 + ParcelArgumentDecoder 解参数。
                // spec § 9.2:返回 null = 连方法名都没解到,走启发式兜底 + "code=N" 显示。
                // spec 2026-05-03 § 3.3:入参从单 packageName 改为 candidates 列表。
                val signature = methodResolver.getMethodSignature(
                    result.interfaceName,
                    event.code,
                    event.resolveCandidates,
                )
                if (signature == null) {
                    val sniffed = parcelParser.sniffArgumentTypes(event.rawParcel)
                    event.sniffedSignature = sniffed
                    val code = "code=${event.code}"
                    event.methodName = if (sniffed.isEmpty()) code else "$code (${sniffed.joinToString(", ")})"
                    return
                }
                event.methodName = signature.methodName
                // spec § 9.3:按 paramTypes 顺序解参数值。零参方法不调,免白白 Parcel.obtain。
                if (signature.paramTypes.isNotEmpty()) {
                    val decResult = argumentDecoder.decode(event.rawParcel, signature.paramTypes)
                    event.parsedArgs = decResult.args
                }
            }
        }
    }

    /**
     * 单元测试钩子:跳过 [parseEvent] 直接放入 buffer,用于 [findReplyForRequest] 等
     * 不依赖解析路径的逻辑测试。生产路径不要用 —— 跳过 parseEvent 后事件没有
     * interfaceName / methodName / direction / decodeSource,会污染 CoverageStats
     * 与 UI 显示。@VisibleForTesting(NONE) 让 Lint 在生产代码引用时报错。
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal fun addEventDirectForTest(event: BinderEvent) {
        synchronized(lock) {
            coverageStats.onEventAdded(event)
            buffer.add(event)
            _eventCount.value = ++totalCount
        }
    }

    /**
     * 单元测试钩子:直接调用 [computeResolveCandidates] 内部逻辑,可临时覆盖 targetPackage。
     * 不影响实例真实状态。
     */
    internal fun computeResolveCandidatesForTest(
        senderPkg: String?,
        interfaceName: String?,
        targetPackageOverride: String? = null,
    ): List<String> {
        val origin = targetPackage
        if (targetPackageOverride != null) targetPackage = targetPackageOverride
        try {
            return computeResolveCandidates(senderPkg, interfaceName)
        } finally {
            if (targetPackageOverride != null) targetPackage = origin
        }
    }

    /**
     * spec 2026-05-03 § 3.2:计算候选包列表(顺序去重,空跳过)。
     *
     * 优先级:
     *   1. [targetPackage](被监控 App)
     *   2. [InterfaceIndex.lookupOrdered](按首次插入顺序的索引命中)
     *   3. [senderPkg](sender uid 反查包名)
     *
     * 返回不可变 list。三档都空 → emptyList。
     */
    private fun computeResolveCandidates(senderPkg: String?, interfaceName: String?): List<String> {
        val out = LinkedHashSet<String>()
        targetPackage?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        if (!interfaceName.isNullOrEmpty()) {
            for (p in interfaceIndex.lookupOrdered(interfaceName)) {
                if (p.isNotEmpty()) out.add(p)
            }
        }
        senderPkg?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out.toList()
    }

    /**
     * 设置过滤条件。过滤器变化需要立即反映,不等下一个 tick。
     */
    fun setFilter(filter: EventFilter) {
        CLogUtils.d(TAG, "setFilter() interface='${filter.interfaceContains}', method='${filter.methodContains}'")
        _currentFilter.value = filter
        scope.launch { emitSnapshot() }
    }

    fun clearFilter() {
        CLogUtils.d(TAG, "clearFilter() 清除过滤条件")
        setFilter(EventFilter())
    }

    /**
     * 构建并发射一次快照。buffer.snapshot() 自身已加锁返回不可变 list,
     * 过滤与 StateFlow 写入完全在锁外,与生产者热路径解耦。
     */
    private fun emitSnapshot() {
        val filter = _currentFilter.value
        val all = synchronized(lock) { buffer.snapshot() }
        // UI 列表层默认隐藏「matched reply」(已配对到 request 的 reply 帧)。
        // 这类 reply 信息已经通过 LinkedReplySection 在 request 详情页完整呈现,
        // 列表里再单独展示一条 reply item 视觉冗余。
        // 保留:isReply 但 pairId == 0(orphan reply,daemon 没解出 pair),仍单独展示
        // 让用户感知"有 reply 但配对失败"。
        // 注意:matched reply 在 buffer 里保留不删,linkedReply 反查依赖 buffer 全量。
        val visible = all.filter { !it.isReply || it.pairId == 0L }
        val snapshot = if (filter.isEmpty()) visible else visible.filter { filter.matches(it) }
        _filteredEvents.value = snapshot
        // CoverageStats 自身字段读取无锁(AtomicLong),与 emitSnapshot 节流到 25Hz
        // 一起,UI 端覆盖率卡片每 40ms 收一次新值,不抖也不卡。
        _coverage.value = coverageStats.snapshot()
    }

    /**
     * 清空所有事件
     */
    fun clearEvents() {
        CLogUtils.i(TAG, "clearEvents() 清空所有事件")
        synchronized(lock) {
            // buffer 未挂 onEvicted,clear 不回调;coverageStats.reset() 显式把全量累计归零。
            buffer.clear()
            coverageStats.reset()
            totalCount = 0
            _eventCount.value = 0
            _eventRate.value = 0
            lastSecondCount = 0
            lastSecondTime = System.currentTimeMillis()
        }
        _filteredEvents.value = emptyList()
        _coverage.value = CoverageSnapshot.EMPTY
        dirty.set(false)
        // 冷层清空放锁外异步:DELETE 是 IO,别阻塞调用线程(MonitorViewModel.clearEvents 非协程)。
        scope.launch { coldStore?.clear() }
    }

    /**
     * 获取事件详情 —— 先 O(1) 查内存热层,miss 再穿透冷层(落盘的老事件)。
     */
    suspend fun getEvent(eventId: Long): BinderEvent? {
        synchronized(lock) { buffer.findById(eventId) }?.let { return it }
        return coldStore?.getById(eventId)
    }

    /**
     * 反查与 [request] 配对的 reply 事件。
     *
     * 配对规则(spec § 6.3.1 + spec 2026-05-09 § 3):
     *   - request.isReply == true → 直接返回 null(reply 不再有 reply)
     *   - request.pairId == 0L    → daemon 端无配对 ID(早期内核 / 协议),返回 null
     *   - 否则在内存事件 buffer 里扫一次,匹配第一条 isReply == true 且 pairId 相等的事件。
     *     合法 binder 流里同一 pairId 至多 1 条 reply;极小概率 daemon 上报重复时,
     *     按 deque FIFO 顺序取先入队的那条以保证幂等。
     *
     * 扫描走 [BoundedEventBuffer.findFirst] 锁内遍历,不构造 List 副本 ——
     * 容量上限 50 000 时也 < 1ms,与 emitSnapshot 同模式不破坏并发不变量。
     *
     * 热层 miss 时穿透 [coldStore] 冷层(reply 已被堆压转存落盘的情况)。
     *
     * **边界**:reply 尚未到达 / 冷热层都 miss 时返回 null,UI 退化到「等待 reply…」
     * 分支(LinkedReplySection 的文案已覆盖该可能性)。
     */
    suspend fun findReplyForRequest(request: BinderEvent): BinderEvent? {
        if (request.isReply) return null
        if (request.pairId == 0L) return null
        val targetPairId = request.pairId
        synchronized(lock) {
            buffer.findFirst { it.isReply && it.pairId == targetPairId }
        }?.let { return it }
        return coldStore?.findPair(targetPairId, wantReply = true)
    }

    /**
     * 反向查找:reply 的 pairId → 配对 request,与 findReplyForRequest 对称。
     * reply 详情页用,填充「请求数据」section。reply 帧的 pairId 由 BPF 端在 reply 路径
     * 直接写入(spec 2026-05-09 daemon-reply-uid-filter),与 request 的 pairId 同源。
     *
     * 热层 miss 时穿透 [coldStore] 冷层(request 已被堆压转存落盘的情况)。
     *
     * **边界**:冷热层都 miss 时返回 null,UI 退化到「配对 request 已被淘汰」分支
     * (orphan reply 同等)。
     */
    suspend fun findRequestForReply(reply: BinderEvent): BinderEvent? {
        if (!reply.isReply) return null
        if (reply.pairId == 0L) return null
        val targetPairId = reply.pairId
        synchronized(lock) {
            buffer.findFirst { !it.isReply && it.pairId == targetPairId }
        }?.let { return it }
        return coldStore?.findPair(targetPairId, wantReply = false)
    }
}
