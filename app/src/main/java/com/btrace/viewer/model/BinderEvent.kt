package com.btrace.viewer.model

import com.btrace.viewer.parser.DecodedArgument
import com.btrace.viewer.parser.ReplyParser
import com.btrace.viewer.parser.decoders.Confidence
import com.btrace.viewer.parser.decoders.DecodeSource
import com.btrace.viewer.utils.CLogUtils
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Binder设备类型 (spec § 6.2)
 */
enum class BinderDev {
    UNKNOWN, BINDER, HWBINDER, VNDBINDER;

    companion object {
        fun fromByte(b: Byte): BinderDev = when (b.toInt()) {
            1 -> BINDER
            2 -> HWBINDER
            3 -> VNDBINDER
            else -> UNKNOWN
        }
    }
}

/**
 * Binder 调用目标种类 (spec § 6.2)
 */
enum class TargetKind {
    UNKNOWN, HANDLE, PTR;

    companion object {
        fun fromByte(b: Byte): TargetKind = when (b.toInt()) {
            1 -> HANDLE
            2 -> PTR
            else -> UNKNOWN
        }
    }
}

/**
 * 事件相对"目标 App"的方向 (spec § 3.2.8 / § 6.5)。
 *
 * 由 [EventRepository.parseEvent] 在拿到 sender uid / to uid / isReply 与当前监控目标 uid 后,
 * 经 [infer] 推断填入。当前 BPF 在引入 vmlinux.h 之前 to_uid 恒为 0,
 * 入向方向(INCOMING_*)会暂时无法被命中,事件按 OUTGOING_* / INCOMING_REPLY 兜底显示。
 */
enum class Direction {
    /** 目标 App 作为 client 发出请求 */
    OUTGOING_REQUEST,
    /** 目标 App 作为 server 收到请求(spec § 3.2.8 入向调用) */
    INCOMING_REQUEST,
    /** 目标 App 作为 server 在回复(reply 中目标是原 callee → reply 的 sender) */
    OUTGOING_REPLY,
    /** 目标 App 作为 client 收到回复 */
    INCOMING_REPLY,
    /** targetUid 未设或无足够信息 → UI 退化为不显示方向区分 */
    UNKNOWN;

    companion object {
        /**
         * 根据 sender uid / to uid / isReply / 当前 targetUid 推断方向。
         *
         * - targetUid <= 0 (未设)→ UNKNOWN。
         * - toUid == 0 (BPF 暂未填,本期常态)→ 无法判 INCOMING,按 sender/isReply 兜底。
         */
        fun infer(senderUid: Int, toUid: Int, isReply: Boolean, targetUid: Int): Direction {
            if (targetUid <= 0) return UNKNOWN
            val senderHit = senderUid == targetUid
            val toHit = toUid != 0 && toUid == targetUid
            return when {
                isReply && senderHit -> OUTGOING_REPLY
                isReply && toHit     -> INCOMING_REPLY
                isReply              -> INCOMING_REPLY  // toUid 缺失 + sender 不命中:reply 兜底为 INCOMING
                senderHit            -> OUTGOING_REQUEST
                toHit                -> INCOMING_REQUEST
                else                 -> OUTGOING_REQUEST  // 双向过滤兜底
            }
        }
    }
}

/**
 * spec § 6.5:flags 最低位 = oneway。抽出来共用,方便 UI badge 与单测引用。
 *
 * Binder 协议把 `transact()` 的 `flag` 复用了 `IBinder.FLAG_ONEWAY` (0x00000001)。
 * 高位还有 `FLAG_PRIVATE_VENDOR` 等,但本期只关心 oneway / twoway 二态。
 */
fun isOneway(flags: Int): Boolean = (flags and 0x1) == 0x1

/**
 * Binder事件数据模型
 *
 * 协议 v2 payload 头长 60 字节(spec § 6.2),fromPayload 按 v2 严格解析,
 * 旧 v1(28B)在 P3 阶段已不再支持。
 */
data class BinderEvent(
    val id: Long,
    val timestamp: Long,      // 纳秒时间戳
    val pid: Int,             // 进程ID
    val uid: Int,             // 用户ID
    val code: Int,            // 方法Code
    val flags: Int,           // Binder Flags
    val rawParcel: ByteArray, // Parcel原始数据
    // 以下为 v2 协议新增字段(spec § 6.2)。createMock / 旧测试代码不传时取默认值。
    val isReply: Boolean = false,
    val binderDev: BinderDev = BinderDev.UNKNOWN,
    val targetKind: TargetKind = TargetKind.UNKNOWN,
    val toPid: Int = 0,
    val toUid: Int = 0,
    val targetRef: Long = 0L,
    val pairId: Long = 0L,
    // spec 2026-05-03 § 4.4:可选 STACK_TRACE TLV;无栈时为 null。
    val stackTrace: StackTrace? = null
) {
    // 缓存解析结果
    private var _interfaceName: String? = null
    private var _callerPackage: String? = null
    private var _methodName: String? = null

    /**
     * 目标进程的可读名(server 端):
     *   - app uid (>=10000):PackageManager 反查得到的包名,如 com.miui.audiomonitor
     *   - 系统 uid (<10000):SystemUidNames 助记名,如 "system" / "audioserver"
     *   - 反查失败 / toUid==0:null
     *
     * EventRepository.parseEvent 在拿到 toUid 后填入。与 callerPackage(基于 sender uid)
     * 互补:request 帧上 callerPackage=client 包,toPackage=server 包/系统名。
     */
    @Volatile
    var toPackage: String? = null

    // spec § 10:结构化参数解码结果。EventRepository 在 parseEvent 时填入,
    // UI 列表页展示前 1-2 条摘要,详情页展示全量。
    @Volatile
    var parsedArgs: List<DecodedArgument> = emptyList()

    // 参数签名指纹(启发式兜底)。仅在 parsedArgs 为空时用来补一个"code=N (String, IBinder)"
    // 的可读提示,和 parsedArgs 互斥 —— 方法签名解到了就用精确值,解不到才用启发式指纹。
    @Volatile
    var sniffedSignature: List<String> = emptyList()

    // spec § 6.5:相对监控目标的方向。EventRepository.parseEvent 在拿到 targetUid 后填入。
    @Volatile
    var direction: Direction = Direction.UNKNOWN

    // spec § 6.5:命中的探测器档位,供 CoverageStats 聚合到 5 类显示(AIDL/HIDL/Reply/特殊 code/Unknown)。
    // EventRepository.parseEvent 在 decodePipeline 命中后回填;UI 进入 CoverageStats 前为 null。
    @Volatile
    var decodeSource: DecodeSource? = null

    // spec § 6.5:解析置信度。EventRepository.parseEvent 在 decodePipeline 命中后回填,
    // UI 列表项里 confidence != HIGH 时显示浅灰小角标 `?`,提示用户结果可能不精确。
    @Volatile
    var confidence: Confidence? = null

    // spec § 6.3:Java AIDL 同步 reply 解码结果(异常 / 返回值 / raw hex 摘要)。
    // ReplyDecoder 在命中 pairer 后通过 ReplyParser 填入,详情页据此展示"返回值/异常"行。
    // 非 reply 事件、HIDL reply、未命中 pairer 的 reply 一律 null。
    @Volatile
    var parsedReply: ReplyParser.ReplyDecodeResult? = null

    /**
     * spec 2026-05-03 § 3.1 / § 3.2:私有 AIDL 解析候选包列表(顺序去重)。
     *
     * 由 [com.btrace.viewer.data.EventRepository.parseEvent] 一次性算好:
     *   1. 被监控 App(targetUid 反查得到的 packageName)
     *   2. [com.btrace.viewer.parser.InterfaceIndex.lookupOrdered] 命中的候选包
     *   3. sender 包(callerPackage,等价于 event.uid 反查得到的 packageName)
     *
     * 与显示用的 [callerPackage] **解耦**:callerPackage 仍按 sender uid 反查,
     * 用于 UI 展示;resolveCandidates 用于 [com.btrace.viewer.parser.MethodResolver]
     * 解析方法签名时按优先级遍历。空列表表示三档全空(MethodResolver 走 framework
     * loader 路径,等价于现状)。
     */
    @Volatile
    var resolveCandidates: List<String> = emptyList()

    /**
     * 接口名（懒加载）
     */
    var interfaceName: String
        get() = _interfaceName ?: "Unknown"
        set(value) { _interfaceName = value }

    /**
     * 调用方包名（懒加载）
     */
    var callerPackage: String
        get() = _callerPackage ?: "UID:$uid"
        set(value) { _callerPackage = value }

    /**
     * 方法名（懒加载）
     */
    var methodName: String
        get() = _methodName ?: "code=$code"
        set(value) { _methodName = value }

    /**
     * 被调用方（接口名的简短形式）
     */
    val callee: String get() = interfaceName.substringAfterLast(".")

    /** 详情页和导出的方向文本,与方向 badge 使用同一套语义。 */
    val directionLabel: String
        get() {
            val resolved = if (direction == Direction.UNKNOWN) {
                if (isReply) Direction.INCOMING_REPLY else Direction.OUTGOING_REQUEST
            } else direction
            return when (resolved) {
                Direction.OUTGOING_REQUEST -> "→ 请求 (出向)"
                Direction.INCOMING_REQUEST -> "← 请求 (入向)"
                Direction.OUTGOING_REPLY -> "→ 回复 (出向)"
                Direction.INCOMING_REPLY -> "← 回复 (入向)"
                Direction.UNKNOWN -> "未知"
            }
        }

    /**
     * 格式化时间戳显示
     */
    val formattedTime: String
        get() {
            val millis = timestamp / 1_000_000 // 纳秒转毫秒
            val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            return format.format(Date(millis))
        }

    /**
     * 格式化完整时间戳
     */
    val formattedFullTime: String
        get() {
            val millis = timestamp / 1_000_000
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            return format.format(Date(millis))
        }

    /**
     * Flags的十六进制显示
     */
    val flagsHex: String get() = "0x${flags.toString(16).padStart(8, '0').uppercase()}"

    /**
     * targetRef 的十六进制显示
     */
    val targetRefHex: String get() = "0x${targetRef.toString(16).uppercase()}"

    companion object {
        private const val TAG = "BinderEvent"
        private var idCounter = 0L

        // spec § 6.2:v2 头部固定 60 字节。
        private const val HEADER_LEN_V2 = 60
        private const val PROTO_VERSION_V2: Byte = 2

        // spec 2026-05-03 § 4.4:ext_flags + STACK_TRACE TLV
        private const val EXT_FLAG_HAS_STACK_TLV: Int = 0x01
        private const val TLV_TYPE_STACK_TRACE: Byte = 0x01
        private const val TLV_HEADER_LEN = 5 // 1B type + 4B length BE

        // spec § 4.4.4:5 级 fail-open guard 静默计数,与 daemon 端 tlvGuard 一一对齐。
        // **绝不**在 G1/G2/G4/G5 路径写日志,只静默累加(供 Settings 高级面板观测)。
        // app 端没有 telemetry 上报 daemon 的反向通道,这里仅做进程内可读。
        @JvmStatic val tlvGuardG1Count = java.util.concurrent.atomic.AtomicLong(0)
        @JvmStatic val tlvGuardG2Count = java.util.concurrent.atomic.AtomicLong(0)
        @JvmStatic val tlvGuardG4Count = java.util.concurrent.atomic.AtomicLong(0)
        @JvmStatic val tlvGuardG5Count = java.util.concurrent.atomic.AtomicLong(0)

        /**
         * 从协议Payload解析事件。对不合法的 payload 返回 null,由调用方决定忽略还是上报。
         *
         * v2 字节布局(Big Endian, spec § 6.2):
         *  ```
         *   0  1B  version          = 2
         *   1  1B  ext_flags        = 0
         *   2  2B  reserved0
         *   4  8B  timestamp_ns
         *  12  4B  pid (sender)
         *  16  4B  uid (sender)
         *  20  4B  code
         *  24  4B  flags
         *  28  4B  data_size
         *  32  1B  is_reply         (0 / 1)
         *  33  1B  binder_dev       (0=UNKNOWN, 1=BINDER, 2=HWBINDER, 3=VNDBINDER)
         *  34  1B  target_kind      (0=UNKNOWN, 1=HANDLE, 2=PTR)
         *  35  1B  reserved1
         *  36  4B  to_pid
         *  40  4B  to_uid
         *  44  8B  target_ref
         *  52  8B  pair_id
         *  60  ... parcel data
         *  ```
         */
        fun fromPayload(payload: ByteArray): BinderEvent? {
            CLogUtils.v(TAG, "fromPayload() 开始解析, payloadSize=${payload.size}")

            // === Guard 1:base 头本身完整(payloadLen >= 60)===
            // **必须**先校验 payloadLen >= 60,**再**读 ext_flags / data_size,否则越界 UB
            // (spec § 4.4.4 BLOCKER B2 不变量)。
            // **静默 fail-open**:spec § 4.4.4 line 619 "绝不报错日志(避免日志洪流)",
            // 损坏头一上来不能让 logcat 雪崩,只 tlvGuardG1Count 累加 + v 级别留痕。
            if (payload.size < HEADER_LEN_V2) {
                tlvGuardG1Count.incrementAndGet()
                CLogUtils.v(TAG, "fromPayload() G1 payload过短: ${payload.size} < $HEADER_LEN_V2,静默丢弃")
                return null
            }

            val buffer = ByteBuffer.wrap(payload)
            val version = buffer.get()
            if (version != PROTO_VERSION_V2) {
                CLogUtils.w(TAG, "fromPayload() 不支持的 protocol version=$version (期望 $PROTO_VERSION_V2), 丢弃")
                return null
            }
            val extFlags = buffer.get()
            buffer.short                             // reserved0
            val timestamp = buffer.long
            val pid = buffer.int
            val uid = buffer.int
            val code = buffer.int
            val flags = buffer.int
            val dataSize = buffer.int
            val isReplyByte = buffer.get()
            val binderDevByte = buffer.get()
            val targetKindByte = buffer.get()
            buffer.get()                             // reserved1
            val toPid = buffer.int
            val toUid = buffer.int
            val targetRef = buffer.long
            val pairId = buffer.long

            CLogUtils.v(
                TAG,
                "fromPayload() v2 头: ts=$timestamp pid=$pid uid=$uid code=$code flags=0x${flags.toString(16)} " +
                    "dataSize=$dataSize isReply=$isReplyByte binderDev=$binderDevByte targetKind=$targetKindByte " +
                    "toPid=$toPid toUid=$toUid targetRef=0x${targetRef.toString(16)} pairId=$pairId extFlags=$extFlags"
            )

            // === Guard 2:ParcelData 完整(payloadLen >= 60 + data_size)===
            // 同 G1:静默 fail-open,绝不写 W 级日志(spec § 4.4.4)。
            val baseEnd = HEADER_LEN_V2 + dataSize
            if (dataSize < 0 || baseEnd > payload.size) {
                tlvGuardG2Count.incrementAndGet()
                CLogUtils.v(TAG, "fromPayload() G2 dataSize 不合法: dataSize=$dataSize, payloadSize=${payload.size},静默丢弃")
                return null
            }

            val parcelData = ByteArray(dataSize)
            if (dataSize > 0) {
                buffer.get(parcelData)
                CLogUtils.v(TAG, "fromPayload() parcel数据: ${CLogUtils.formatBytes(parcelData, 32)}")
            }

            // === Guard 3 / 4 / 5:可选 STACK_TRACE TLV(spec § 4.4.4)===
            // 任一 guard 失败一律返回 stackTrace=null,**绝不**抛异常 / 不报错日志(避免日志洪流)
            val stackTrace = parseStackTraceTLV(payload, baseEnd, extFlags.toInt() and 0xff)

            val event = BinderEvent(
                id = idCounter++,
                timestamp = timestamp,
                pid = pid,
                uid = uid,
                code = code,
                flags = flags,
                rawParcel = parcelData,
                isReply = isReplyByte == 1.toByte(),
                binderDev = BinderDev.fromByte(binderDevByte),
                targetKind = TargetKind.fromByte(targetKindByte),
                toPid = toPid,
                toUid = toUid,
                targetRef = targetRef,
                pairId = pairId,
                stackTrace = stackTrace
            )

            CLogUtils.d(
                TAG,
                "fromPayload() 事件创建完成: id=${event.id}, pid=$pid, uid=$uid, code=$code, isReply=${event.isReply}, hasStack=${stackTrace != null}"
            )
            return event
        }

        /**
         * 解析可选 STACK_TRACE TLV(spec § 4.4.4 5 级 fail-open guard 链)。
         *
         * Guard 3: ext_flags & 0x01 → 无 TLV,正常返回 null
         * Guard 4: payloadLen >= baseEnd + 5(TLV 头完整)
         * Guard 5: tlv_length <= payloadLen - baseEnd - 5(payload 不越界)
         *
         * 每一级失败都返回 null,**绝不**抛异常。
         * Daemon Go 与 app Kotlin 必须**逐行对齐**5 级 guard 顺序(spec § 4.4.4 一致性约束)。
         */
        private fun parseStackTraceTLV(payload: ByteArray, baseEnd: Int, extFlags: Int): StackTrace? {
            // === Guard 3 ===
            if ((extFlags and EXT_FLAG_HAS_STACK_TLV) == 0) {
                return null
            }
            // === Guard 4 ===
            if (payload.size < baseEnd + TLV_HEADER_LEN) {
                tlvGuardG4Count.incrementAndGet()
                return null
            }
            val tlvType = payload[baseEnd]
            // tlv_length 必须按 unsigned 读
            val tlvLength: Long = ByteBuffer.wrap(payload, baseEnd + 1, 4).int.toLong() and 0xFFFFFFFFL

            if (tlvType != TLV_TYPE_STACK_TRACE) {
                // 未知 TLV 类型 → 跳过(向前兼容,不计入 guard 失败)
                return null
            }
            // === Guard 5 ===
            val maxTlvLen = (payload.size - baseEnd - TLV_HEADER_LEN).toLong()
            if (tlvLength > maxTlvLen) {
                tlvGuardG5Count.incrementAndGet()
                return null
            }
            val tlvStart = baseEnd + TLV_HEADER_LEN
            return runCatching {
                StackTraceCodec.decode(payload, tlvStart, tlvLength.toInt())
            }.onFailure { e ->
                CLogUtils.v(TAG, "parseStackTraceTLV() decode threw, fail-open: ${e.message}")
            }.getOrNull()
        }

        /**
         * 创建模拟事件（用于UI测试）
         */
        fun createMock(
            interfaceName: String,
            methodName: String,
            callerPackage: String,
            uid: Int = 10086,
            pid: Int = 1234
        ): BinderEvent {
            CLogUtils.d(TAG, "createMock() interface=$interfaceName, method=$methodName")
            return BinderEvent(
                id = idCounter++,
                timestamp = System.currentTimeMillis() * 1_000_000,
                pid = pid,
                uid = uid,
                code = 1,
                flags = 0,
                rawParcel = ByteArray(0)
            ).apply {
                this.interfaceName = interfaceName
                this.methodName = methodName
                this.callerPackage = callerPackage
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BinderEvent
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
