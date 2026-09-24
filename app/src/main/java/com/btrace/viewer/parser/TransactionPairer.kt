package com.btrace.viewer.parser

import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.parser.decoders.DecodeSource
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * spec § 6.3.2 请求/回复配对。
 *
 * 进程内维护两张表,分别记录"目标 App 作为 client 发出的请求"与"目标 App 作为 server
 * 收到的请求",key 都用 daemon 在协议 v2 里写入的稳定 [BinderEvent.pairId]。当
 * `is_reply == 1` 的事件到达时,按方向 O(1) 查表配对,命中即继承请求侧的 interfaceName /
 * methodName,让 reply 帧不再以 "(reply)" 这种半残形式展示。
 *
 * 方向判定:
 * - 目标 App 是 sender(`uid == targetUid`)→ 该请求是目标 App 自己发出 → 落 [clientRequests];
 *   它的 reply 由对端发回,reply 的 sender 是对端、`to_uid` 是目标 App,所以 reply 命中
 *   `to_uid == targetUid` 时去 [clientRequests] 找。
 * - 目标 App 是 receiver(`to_uid == targetUid`)→ 该请求是别人调目标 App → 落
 *   [serverRequests];目标 App 回复时 sender 就是它自己,reply 的 `uid == targetUid`,所以
 *   reply 命中 sender 时去 [serverRequests] 找。
 *
 * 当前 BPF 在引入 vmlinux.h 之前 `to_uid` 恒为 0(spec § 7 P4 待做),双向过滤实际只能命中
 * "目标 App 是 sender"这一半,本类按 BinderEvent 字段写,不硬编码;待 P4 落地后无需改动。
 *
 * **驱逐策略**:增量式,每次 [recordRequest] 触发一次 evict——
 *   1. 先按 TTL([ttlMs])扫,过期条目直接移除;
 *   2. 若仍 > [maxEntries],按 enqueuedAt 最早的依次淘汰直到达标(LRU 近似)。
 *
 * **线程安全**:两张表都用 [ConcurrentHashMap],读写本身原子;evict 阶段对单张表加 [Any]
 * 锁(`clientLock` / `serverLock`),保证 size cap 在并发 put 时不会被穿透。配对侧
 * ([tryMatchReply])用 ConcurrentHashMap.remove 即可。
 *
 * **可测时序**:[clockMs] 由外部注入(默认 [System.currentTimeMillis]),单测可注入假时钟
 * 直接验证 TTL 行为,无需 sleep。
 */
@Singleton
class TransactionPairer @Inject constructor() {

    constructor(clockMs: () -> Long) : this() {
        this.clockProvider = clockMs
    }

    /** TTL 与上限来自 spec § 6.3.2,设计文档已固定。 */
    companion object {
        const val TTL_MS = 5_000L
        const val MAX_ENTRIES = 4096
    }

    /** 回复解析需要的请求信息。不保留原始 Parcel,避免配对表占用过多内存。 */
    data class PairResult(
        val interfaceName: String?,
        val methodName: String?,
        val code: Int,
        val resolveCandidates: List<String>,
        val decodeSource: DecodeSource?,
    )

    /** 一条 pending 请求。enqueuedAtMs 用于 TTL / LRU。 */
    private data class PendingRequest(
        val result: PairResult,
        val enqueuedAtMs: Long,
    )

    private var clockProvider: () -> Long = { System.currentTimeMillis() }

    private val clientRequests = ConcurrentHashMap<Long, PendingRequest>()
    private val serverRequests = ConcurrentHashMap<Long, PendingRequest>()

    private val clientLock = Any()
    private val serverLock = Any()

    /** 仅供测试:总条目数。 */
    internal fun size(): Int = clientRequests.size + serverRequests.size

    /** 仅供测试:client 表条目数。 */
    internal fun clientSize(): Int = clientRequests.size

    /** 仅供测试:server 表条目数。 */
    internal fun serverSize(): Int = serverRequests.size

    /**
     * 把请求落入对应方向的表;reply 帧 / pairId == 0 / 没拿到方向 自动 noop。
     *
     * 调用方契约:在请求解析完成后调一次,过滤逻辑全在本方法里,
     * 让 ParcelParser 不必理会方向判断。
     */
    fun recordRequest(event: BinderEvent, targetUid: Int) {
        if (event.isReply) return
        if (event.pairId == 0L) return
        if (targetUid <= 0) return

        val senderHit = event.uid == targetUid
        val toHit = event.toUid != 0 && event.toUid == targetUid
        // 既不是 sender 也不是 receiver(过滤兜底场景)→ 与目标 App 无关,不落表
        if (!senderHit && !toHit) return

        val now = clockProvider()
        val entry = PendingRequest(
            result = PairResult(
                interfaceName = event.interfaceName.takeIf { it != "Unknown" },
                methodName = event.methodName.takeIf { it != "code=${event.code}" },
                code = event.code,
                resolveCandidates = event.resolveCandidates,
                decodeSource = event.decodeSource,
            ),
            enqueuedAtMs = now,
        )

        if (senderHit) {
            putAndEvict(clientRequests, clientLock, event.pairId, entry, now)
        } else {
            putAndEvict(serverRequests, serverLock, event.pairId, entry, now)
        }
    }

    /**
     * 给 ReplyDecoder 调:reply 命中且 pairId != 0 时按方向查表;命中 remove + 返回 PairResult,
     * 未命中返回 null。pairId == 0 / 非 reply / targetUid 未设 → 一律 null,让上层走 orphan 兜底。
     */
    fun tryMatchReply(event: BinderEvent, targetUid: Int): PairResult? {
        if (!event.isReply) return null
        if (event.pairId == 0L) return null
        if (targetUid <= 0) return null

        // reply 方向反过来:目标 App 是 sender(自己回复)→ 当时它是 server → 查 serverRequests
        //                  目标 App 是 receiver(收到回复)→ 当时它是 client → 查 clientRequests
        val senderHit = event.uid == targetUid
        val toHit = event.toUid != 0 && event.toUid == targetUid

        val pending = when {
            toHit    -> clientRequests.remove(event.pairId)
            senderHit -> serverRequests.remove(event.pairId)
            // toUid 缺失场景(P4 未落值)兜底:reply 的 sender 不是目标 App → 当时目标 App
            // 一定是 client(因为目标 App 是 client 时它的 reply 由对端发回),查 clientRequests
            else     -> clientRequests.remove(event.pairId)
        } ?: return null

        // TTL 过期(进了表但 evict 没来得及扫)→ 视作未命中,丢弃
        if (clockProvider() - pending.enqueuedAtMs > TTL_MS) return null

        return pending.result
    }

    private fun putAndEvict(
        table: ConcurrentHashMap<Long, PendingRequest>,
        lock: Any,
        pairId: Long,
        entry: PendingRequest,
        now: Long,
    ) {
        // put 自身原子,先写;evict 阶段才需要互斥(否则两个并发 put 看到同样 size 都跳过驱逐)
        table[pairId] = entry

        synchronized(lock) {
            // 1. TTL 扫描:过期一律剔除
            val cutoff = now - TTL_MS
            val it = table.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.value.enqueuedAtMs < cutoff) {
                    it.remove()
                }
            }

            // 2. 容量上限:LRU 淘汰最早入表的若干条
            if (table.size > MAX_ENTRIES) {
                // sortedBy 拷一份 entries,持锁期间不会有别人改 table
                val sorted = table.entries.sortedBy { it.value.enqueuedAtMs }
                val excess = table.size - MAX_ENTRIES
                for (i in 0 until excess) {
                    table.remove(sorted[i].key)
                }
            }
        }
    }
}
