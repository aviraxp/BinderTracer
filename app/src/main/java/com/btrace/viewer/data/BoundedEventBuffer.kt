package com.btrace.viewer.data

import com.btrace.viewer.model.BinderEvent

/**
 * 固定容量的 FIFO 事件缓冲。
 *
 * 从 [EventRepository] 抽出来的"环形"逻辑:容量满时 head 出队,O(1) 索引查 id,容量调小时
 * 立刻裁掉超额的最老事件。
 *
 * **不是**线程安全的 —— 调用方(EventRepository)用 monitor lock 串行化。这里保持纯逻辑
 * 是为了 JVM 单测能不依赖 Android 跑通。
 */
class BoundedEventBuffer(capacity: Int) {

    init { require(capacity > 0) { "capacity must be > 0, got $capacity" } }

    @Volatile
    private var capacity: Int = capacity

    // 预分配只是省 rehash 的性能优化:直接按 capacity 算会在 UNLIMITED(Int.MAX_VALUE)时
    // 溢出成负数 → ArrayDeque/HashMap 构造抛 IllegalArgumentException。夹到 64K 上界,
    // 超出部分交给容器自动扩容。
    private val prealloc = capacity.coerceAtMost(1 shl 16)
    private val deque = ArrayDeque<BinderEvent>(prealloc + 16)
    private val index = HashMap<Long, BinderEvent>(prealloc * 2)

    /**
     * 容量满 / setCapacity 调小时被淘汰的事件回调。EventRepository 用它递减
     * CoverageStats 的桶计数,保证 UI 数字与 buffer 实际持有的事件 1:1 对齐。
     */
    @Volatile
    var onEvicted: ((BinderEvent) -> Unit)? = null

    fun add(event: BinderEvent) {
        while (deque.size >= capacity) {
            val removed = deque.removeFirst()
            index.remove(removed.id)
            onEvicted?.invoke(removed)
        }
        deque.addLast(event)
        index[event.id] = event
    }

    fun setCapacity(newCapacity: Int) {
        require(newCapacity > 0) { "capacity must be > 0, got $newCapacity" }
        capacity = newCapacity
        while (deque.size > capacity) {
            val removed = deque.removeFirst()
            index.remove(removed.id)
            onEvicted?.invoke(removed)
        }
    }

    fun capacity(): Int = capacity

    /**
     * 不改变 [capacity],仅把当前 size 削到 <= [target](从最老端淘汰,触发 [onEvicted])。
     * 用于 unlimited(capacity=Int.MAX_VALUE)模式下按堆内存水位做兜底淘汰:此时 FIFO
     * 上限不会触发,只能靠这里主动腾空间。target <= 0 时清空全部。
     *
     * 返回被淘汰的事件(最老→次老顺序),供调用方转存冷层。淘汰点精确收敛在这里,
     * clear / setCapacity 调小的淘汰不落盘(见 EventRepository.maybeTrimForHeap)。
     */
    fun trimToSize(target: Int): List<BinderEvent> {
        val evicted = ArrayList<BinderEvent>()
        while (deque.size > target && deque.isNotEmpty()) {
            val removed = deque.removeFirst()
            index.remove(removed.id)
            evicted.add(removed)
            onEvicted?.invoke(removed)
        }
        return evicted
    }

    fun size(): Int = deque.size

    fun snapshot(): List<BinderEvent> = deque.toList()

    fun findById(id: Long): BinderEvent? = index[id]

    /**
     * 顺序扫描,返回第一个满足谓词的事件;无命中返回 null。
     *
     * 与 [snapshot] 的区别:不构造新 List。容量上限 50 000 时,toList 会一次性
     * 拷贝 ~400 KB 引用,而调用方常常只想要"找到第一条就好",白白付费。配合
     * [EventRepository] 锁内调用,扫描全程加锁 — N=50 000 也 < 1ms。
     */
    fun findFirst(predicate: (BinderEvent) -> Boolean): BinderEvent? {
        for (e in deque) if (predicate(e)) return e
        return null
    }

    fun clear() {
        if (onEvicted != null) {
            // 让 CoverageStats 等订阅者看到批量淘汰,而不是事件凭空消失。
            for (e in deque) onEvicted?.invoke(e)
        }
        deque.clear()
        index.clear()
    }
}
