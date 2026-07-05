package com.btrace.viewer.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.btrace.viewer.model.BinderDev
import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.model.Direction
import com.btrace.viewer.model.StackFrame
import com.btrace.viewer.model.StackQuality
import com.btrace.viewer.model.StackTrace
import com.btrace.viewer.model.TargetKind
import com.btrace.viewer.parser.CoverageBucket
import com.btrace.viewer.parser.DecodedArgument
import com.btrace.viewer.parser.ReplyParser
import com.btrace.viewer.parser.decoders.Confidence
import com.btrace.viewer.parser.decoders.DecodeSource
import com.btrace.viewer.utils.CLogUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** 一页检索结果 + 下一页游标(null = 没有更多)。 */
data class EventPage(val items: List<BinderEvent>, val nextCursor: Long?)

/**
 * BinderEvent ↔ 全量表行 的纯字段映射(标量列直接可查,嵌套对象走 [EventPayloadJson])。
 *
 * 归档**完整**:源数据(raw_parcel)+ 解析结果(标量列 + payload_json)+ 调用栈,
 * 详情/检索与内存实时事件逐字段一致(见 docs/事件缓存-不限量重构方案.md §12)。
 */
internal object ColdEventCodec {

    fun toRow(e: BinderEvent): Map<String, Any?> = mapOf(
        COL_SEQ to e.id,
        COL_PAIR_ID to e.pairId,
        COL_TS to e.timestamp,
        COL_PID to e.pid,
        COL_UID to e.uid,
        COL_CODE to e.code,
        COL_FLAGS to e.flags,
        COL_IS_REPLY to if (e.isReply) 1L else 0L,
        COL_TO_PID to e.toPid,
        COL_TO_UID to e.toUid,
        COL_TARGET_REF to e.targetRef,
        COL_BINDER_DEV to e.binderDev.name,
        COL_TARGET_KIND to e.targetKind.name,
        COL_IFACE to e.interfaceName,
        COL_METHOD to e.methodName,
        COL_CALLER_PKG to e.callerPackage,
        COL_TO_PKG to e.toPackage,
        COL_DIRECTION to e.direction.name,
        COL_DECODE_SOURCE to e.decodeSource?.name,
        COL_CONFIDENCE to e.confidence?.name,
        COL_STACK_MODULES to stackModules(e),
        COL_RAW_PARCEL to e.rawParcel,
        COL_PAYLOAD_JSON to EventPayloadJson.encode(e),
    )

    fun fromRow(r: Map<String, Any?>): BinderEvent {
        val pj = (r[COL_PAYLOAD_JSON] as? String)
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

        val ev = BinderEvent(
            id = (r[COL_SEQ] as Number).toLong(),
            timestamp = (r[COL_TS] as Number).toLong(),
            pid = (r[COL_PID] as Number).toInt(),
            uid = (r[COL_UID] as Number).toInt(),
            code = (r[COL_CODE] as Number).toInt(),
            flags = (r[COL_FLAGS] as Number).toInt(),
            rawParcel = (r[COL_RAW_PARCEL] as? ByteArray) ?: ByteArray(0),
            isReply = (r[COL_IS_REPLY] as Number).toLong() == 1L,
            binderDev = enumOr(r[COL_BINDER_DEV] as? String, BinderDev.UNKNOWN),
            targetKind = enumOr(r[COL_TARGET_KIND] as? String, TargetKind.UNKNOWN),
            toPid = (r[COL_TO_PID] as Number).toInt(),
            toUid = (r[COL_TO_UID] as Number).toInt(),
            targetRef = (r[COL_TARGET_REF] as Number).toLong(),
            pairId = (r[COL_PAIR_ID] as Number).toLong(),
            // 调用栈是不可变构造字段,必须在构造时还原(不能事后 set)。
            stackTrace = pj?.let { EventPayloadJson.stackFrom(it) },
        )
        (r[COL_IFACE] as? String)?.let { ev.interfaceName = it }
        (r[COL_METHOD] as? String)?.let { ev.methodName = it }
        (r[COL_CALLER_PKG] as? String)?.let { ev.callerPackage = it }
        ev.toPackage = r[COL_TO_PKG] as? String
        ev.direction = enumOr(r[COL_DIRECTION] as? String, Direction.UNKNOWN)
        ev.decodeSource = (r[COL_DECODE_SOURCE] as? String)?.let { enumOrNull<DecodeSource>(it) }
        ev.confidence = (r[COL_CONFIDENCE] as? String)?.let { enumOrNull<Confidence>(it) }
        pj?.let { EventPayloadJson.mutableInto(it, ev) }
        return ev
    }

    /** 所有栈帧 module 去重拼接(供 stack_modules LIKE 检索);无栈返回 null。 */
    fun stackModules(e: BinderEvent): String? {
        val st = e.stackTrace ?: return null
        val mods = LinkedHashSet<String>()
        for (f in st.uFrames) if (f.module.isNotEmpty()) mods.add(f.module)
        for (f in st.kFrames) if (f.module.isNotEmpty()) mods.add(f.module)
        return if (mods.isEmpty()) null else mods.joinToString("\n")
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        runCatching { enumValueOf<T>(name) }.getOrNull()

    const val TABLE = "events"
    const val COL_SEQ = "seq"
    const val COL_PAIR_ID = "pair_id"
    const val COL_TS = "ts"
    const val COL_PID = "pid"
    const val COL_UID = "uid"
    const val COL_CODE = "code"
    const val COL_FLAGS = "flags"
    const val COL_IS_REPLY = "is_reply"
    const val COL_TO_PID = "to_pid"
    const val COL_TO_UID = "to_uid"
    const val COL_TARGET_REF = "target_ref"
    const val COL_BINDER_DEV = "binder_dev"
    const val COL_TARGET_KIND = "target_kind"
    const val COL_IFACE = "iface"
    const val COL_METHOD = "method"
    const val COL_CALLER_PKG = "caller_pkg"
    const val COL_TO_PKG = "to_pkg"
    const val COL_DIRECTION = "direction"
    const val COL_DECODE_SOURCE = "decode_source"
    const val COL_CONFIDENCE = "confidence"
    const val COL_STACK_MODULES = "stack_modules"
    const val COL_RAW_PARCEL = "raw_parcel"
    const val COL_PAYLOAD_JSON = "payload_json"
}

/**
 * 嵌套解析对象 ↔ JSON 序列化。这些都是纯数据(标量+String+list),用 org.json(Android 自带)
 * 即可,不用手写二进制。归档时保留,详情页/检索还原,与实时事件一致。
 */
internal object EventPayloadJson {

    fun encode(e: BinderEvent): String {
        val o = JSONObject()
        e.stackTrace?.let { o.put("stack", stackTo(it)) }
        if (e.parsedArgs.isNotEmpty()) o.put("args", argsTo(e.parsedArgs))
        e.parsedReply?.let { o.put("reply", replyTo(it)) }
        if (e.sniffedSignature.isNotEmpty()) o.put("sniffed", JSONArray(e.sniffedSignature))
        if (e.resolveCandidates.isNotEmpty()) o.put("cands", JSONArray(e.resolveCandidates))
        return o.toString()
    }

    /** 从 payload 还原可变字段(parsedArgs/parsedReply/sniffed/cands);调用栈在构造时另走 [stackFrom]。 */
    fun mutableInto(o: JSONObject, e: BinderEvent) {
        o.optJSONArray("args")?.let { e.parsedArgs = argsFrom(it) }
        o.optJSONObject("reply")?.let { e.parsedReply = replyFrom(it) }
        o.optJSONArray("sniffed")?.let { e.sniffedSignature = strList(it) }
        o.optJSONArray("cands")?.let { e.resolveCandidates = strList(it) }
    }

    fun stackFrom(o: JSONObject): StackTrace? {
        val s = o.optJSONObject("stack") ?: return null
        return StackTrace(
            quality = runCatching { StackQuality.valueOf(s.optString("quality")) }.getOrDefault(StackQuality.DEGRADED),
            truncated = s.optInt("truncated"),
            kFrames = framesFrom(s.optJSONArray("k")),
            uFrames = framesFrom(s.optJSONArray("u")),
            failureReason = s.optString("fail", ""),
        )
    }

    private fun stackTo(st: StackTrace): JSONObject = JSONObject().apply {
        put("quality", st.quality.name)
        put("truncated", st.truncated)
        put("k", framesTo(st.kFrames))
        put("u", framesTo(st.uFrames))
        if (st.failureReason.isNotEmpty()) put("fail", st.failureReason)
    }

    private fun framesTo(frames: List<StackFrame>): JSONArray = JSONArray().apply {
        for (f in frames) put(
            JSONObject().put("pc", f.pc).put("m", f.module).put("s", f.symbol).put("o", f.offset)
        )
    }

    private fun framesFrom(arr: JSONArray?): List<StackFrame> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val f = arr.getJSONObject(i)
            StackFrame(f.optLong("pc"), f.optString("m"), f.optString("s"), f.optLong("o"))
        }
    }

    private fun argsTo(args: List<DecodedArgument>): JSONArray = JSONArray().apply {
        for (a in args) put(
            JSONObject()
                .put("i", a.index)
                .put("t", a.declaredType)
                .put("v", a.displayValue)
                .put("st", a.status.name)
                .apply { a.errorMessage?.let { put("e", it) } }
        )
    }

    private fun argsFrom(arr: JSONArray): List<DecodedArgument> =
        (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            DecodedArgument(
                index = a.optInt("i"),
                declaredType = a.optString("t"),
                displayValue = a.optString("v"),
                status = runCatching { DecodedArgument.Status.valueOf(a.optString("st")) }
                    .getOrDefault(DecodedArgument.Status.UNPARSED),
                errorMessage = if (a.has("e")) a.optString("e") else null,
            )
        }

    private fun replyTo(r: ReplyParser.ReplyDecodeResult): JSONObject = JSONObject().apply {
        r.exception?.let { put("ex", it) }
        r.value?.let { put("v", it) }
        r.rawHexHint?.let { put("hex", it) }
    }

    private fun replyFrom(o: JSONObject): ReplyParser.ReplyDecodeResult =
        ReplyParser.ReplyDecodeResult(
            exception = if (o.has("ex")) o.optString("ex") else null,
            value = if (o.has("v")) o.optString("v") else null,
            rawHexHint = if (o.has("hex")) o.optString("hex") else null,
        )

    private fun strList(arr: JSONArray): List<String> =
        (0 until arr.length()).map { arr.optString(it) }
}

/**
 * 全量事件存储(SQLite 单一事实来源)。内存滑动窗口([BoundedEventBuffer])只是它最新一段的缓存;
 * 检索 / 分页 / 详情完整读回都走这里。冷热分层对上层透明(见 docs/事件缓存-不限量重构方案.md)。
 *
 * 写入异步:[offer] 入 [queue],后台协程批量事务落盘,绝不阻塞 addEvent 热路径。
 * 生命周期:进程首次打开即清空上次会话残留([Helper.onOpen]),单次会话不限量、不设磁盘上限。
 */
@Singleton
class ColdEventStore @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        private const val TAG = "ColdEventStore"
        private const val DB_NAME = "btrace_events.db"
        private const val BATCH = 500
    }

    private val helper = Helper(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<BinderEvent>(Channel.UNLIMITED)

    init {
        scope.launch {
            val batch = ArrayList<BinderEvent>(BATCH)
            for (first in queue) {
                batch.add(first)
                while (batch.size < BATCH) {
                    val more = queue.tryReceive().getOrNull() ?: break
                    batch.add(more)
                }
                runCatching { writeBatch(batch) }
                    .onFailure { CLogUtils.w(TAG, "writeBatch 失败,丢弃 ${batch.size} 条: ${it.message}") }
                batch.clear()
            }
        }
    }

    /** 归档单条(非阻塞,fire-and-forget)。全量落盘:每条事件都进这里。 */
    fun offer(event: BinderEvent) {
        queue.trySend(event)
    }

    /** 单测钩子:同步落盘,绕开异步 channel,专测 query 的 SQL 过滤/分页正确性。 */
    @androidx.annotation.VisibleForTesting
    internal fun writeNowForTest(events: List<BinderEvent>) = writeBatch(events)

    /**
     * 检索一页:过滤条件下推 SQL,键集游标分页(seq < cursor,从新到旧)。
     * cursor=null 取最新一页;返回 nextCursor=null 表示到底。
     */
    suspend fun query(filter: EventFilter, cursor: Long?, pageSize: Int): EventPage =
        withContext(Dispatchers.IO) {
            val where = StringBuilder()
            val args = ArrayList<String>()

            // 列表默认隐藏 matched reply(已配对的 reply 帧),与 emitSnapshot 语义一致。
            where.append("(${ColdEventCodec.COL_IS_REPLY} = 0 OR ${ColdEventCodec.COL_PAIR_ID} = 0)")

            if (filter.interfaceContains.isNotBlank()) {
                where.append(" AND ${ColdEventCodec.COL_IFACE} LIKE ?")
                args.add("%${escapeLike(filter.interfaceContains)}%")
            }
            if (filter.methodContains.isNotBlank()) {
                where.append(" AND ${ColdEventCodec.COL_METHOD} LIKE ?")
                args.add("%${escapeLike(filter.methodContains)}%")
            }
            if (filter.stackModuleContains.isNotBlank()) {
                // stackModule 非空时,无栈事件(stack_modules IS NULL)一律不通过,与内存谓词一致。
                where.append(" AND ${ColdEventCodec.COL_STACK_MODULES} LIKE ?")
                args.add("%${escapeLike(filter.stackModuleContains)}%")
            }
            bucketClause(filter.bucketsAllowed)?.let { where.append(" AND ").append(it) }

            if (cursor != null) {
                where.append(" AND ${ColdEventCodec.COL_SEQ} < ?")
                args.add(cursor.toString())
            }

            val rows = helper.readableDatabase.query(
                ColdEventCodec.TABLE, null, where.toString(), args.toTypedArray(),
                null, null, "${ColdEventCodec.COL_SEQ} DESC", pageSize.toString(),
            ).use { c ->
                val out = ArrayList<BinderEvent>(c.count)
                while (c.moveToNext()) out.add(ColdEventCodec.fromRow(cursorToRow(c)))
                out
            }
            val next = if (rows.size < pageSize) null else rows.last().id
            EventPage(rows, next)
        }

    /** 按 id 点查(热层 miss 才走这里)。 */
    suspend fun getById(id: Long): BinderEvent? = withContext(Dispatchers.IO) {
        queryOne("${ColdEventCodec.COL_SEQ} = ?", arrayOf(id.toString()))
    }

    /** 按 pairId 反查配对(wantReply=true 找 reply,false 找 request)。 */
    suspend fun findPair(pairId: Long, wantReply: Boolean): BinderEvent? = withContext(Dispatchers.IO) {
        queryOne(
            "${ColdEventCodec.COL_PAIR_ID} = ? AND ${ColdEventCodec.COL_IS_REPLY} = ?",
            arrayOf(pairId.toString(), if (wantReply) "1" else "0"),
        )
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { helper.writableDatabase.delete(ColdEventCodec.TABLE, null, null) }
        Unit
    }

    /**
     * 允许的桶集合 → decode_source SQL 子句;全 5 桶(不过滤)返回 null。
     * 反向展开 [CoverageBucket.of]:某 DecodeSource 的桶在白名单里才纳入;null decode_source
     * 当 UNKNOWN 桶处理(与 EventFilter.bucketOf 一致)。
     */
    private fun bucketClause(buckets: Set<CoverageBucket>): String? {
        if (buckets.isEmpty() || buckets.size == EventFilter.ALL_BUCKETS.size) return null
        val sources = DecodeSource.values().filter { CoverageBucket.of(it) in buckets }
        val inList = sources.joinToString(",") { "'${it.name}'" }
        val nullClause = if (CoverageBucket.UNKNOWN in buckets) " OR ${ColdEventCodec.COL_DECODE_SOURCE} IS NULL" else ""
        if (inList.isEmpty()) return if (nullClause.isEmpty()) "0" else "${ColdEventCodec.COL_DECODE_SOURCE} IS NULL"
        return "(${ColdEventCodec.COL_DECODE_SOURCE} IN ($inList)$nullClause)"
    }

    // LIKE 通配符转义:用户输入里的 % _ \ 按字面匹配(配合 ESCAPE 需额外声明,这里够用——
    // 过滤输入是 interface/method 子串,罕见含通配符;转义避免 % 被当通配。
    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun writeBatch(events: List<BinderEvent>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (e in events) {
                val cv = ContentValues()
                for ((k, v) in ColdEventCodec.toRow(e)) putValue(cv, k, v)
                db.insertWithOnConflict(ColdEventCodec.TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun queryOne(where: String, args: Array<String>): BinderEvent? {
        helper.readableDatabase.query(
            ColdEventCodec.TABLE, null, where, args, null, null, "${ColdEventCodec.COL_SEQ} ASC", "1",
        ).use { c ->
            if (!c.moveToFirst()) return null
            return ColdEventCodec.fromRow(cursorToRow(c))
        }
    }

    private fun cursorToRow(c: Cursor): Map<String, Any?> {
        val row = HashMap<String, Any?>(c.columnCount)
        for (i in 0 until c.columnCount) {
            row[c.getColumnName(i)] = when (c.getType(i)) {
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_STRING -> c.getString(i)
                Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                else -> null
            }
        }
        return row
    }

    private fun putValue(cv: ContentValues, key: String, value: Any?) {
        when (value) {
            null -> cv.putNull(key)
            is Long -> cv.put(key, value)
            is Int -> cv.put(key, value)
            is String -> cv.put(key, value)
            is ByteArray -> cv.put(key, value)
            else -> cv.put(key, value.toString())
        }
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE ${ColdEventCodec.TABLE} (" +
                    "${ColdEventCodec.COL_SEQ} INTEGER PRIMARY KEY, " +
                    "${ColdEventCodec.COL_PAIR_ID} INTEGER, ${ColdEventCodec.COL_TS} INTEGER, " +
                    "${ColdEventCodec.COL_PID} INTEGER, ${ColdEventCodec.COL_UID} INTEGER, " +
                    "${ColdEventCodec.COL_CODE} INTEGER, ${ColdEventCodec.COL_FLAGS} INTEGER, " +
                    "${ColdEventCodec.COL_IS_REPLY} INTEGER, ${ColdEventCodec.COL_TO_PID} INTEGER, " +
                    "${ColdEventCodec.COL_TO_UID} INTEGER, ${ColdEventCodec.COL_TARGET_REF} INTEGER, " +
                    "${ColdEventCodec.COL_BINDER_DEV} TEXT, ${ColdEventCodec.COL_TARGET_KIND} TEXT, " +
                    "${ColdEventCodec.COL_IFACE} TEXT, ${ColdEventCodec.COL_METHOD} TEXT, " +
                    "${ColdEventCodec.COL_CALLER_PKG} TEXT, ${ColdEventCodec.COL_TO_PKG} TEXT, " +
                    "${ColdEventCodec.COL_DIRECTION} TEXT, ${ColdEventCodec.COL_DECODE_SOURCE} TEXT, " +
                    "${ColdEventCodec.COL_CONFIDENCE} TEXT, ${ColdEventCodec.COL_STACK_MODULES} TEXT, " +
                    "${ColdEventCodec.COL_RAW_PARCEL} BLOB, ${ColdEventCodec.COL_PAYLOAD_JSON} TEXT)"
            )
            db.execSQL("CREATE INDEX idx_pair ON ${ColdEventCodec.TABLE}(${ColdEventCodec.COL_PAIR_ID})")
            db.execSQL("CREATE INDEX idx_decode ON ${ColdEventCodec.TABLE}(${ColdEventCodec.COL_DECODE_SOURCE})")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS ${ColdEventCodec.TABLE}")
            onCreate(db)
        }

        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            // 会话溢出缓存:进程首次打开即清空上次会话残留,避免跨会话 id(idCounter 冷启动从 0 重排)撞车。
            db.delete(ColdEventCodec.TABLE, null, null)
        }
    }
}
