package com.btrace.viewer.parser

import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.parser.decoders.AidlODecoder
import com.btrace.viewer.parser.decoders.AidlPDecoder
import com.btrace.viewer.parser.decoders.AidlQDecoder
import com.btrace.viewer.parser.decoders.DecodeResult
import com.btrace.viewer.parser.decoders.HidlDescriptorDecoder
import com.btrace.viewer.parser.decoders.ParcelDecoder
import com.btrace.viewer.parser.decoders.RawAsciiHeuristicDecoder
import com.btrace.viewer.parser.decoders.ReplyDecoder
import com.btrace.viewer.parser.decoders.SpecialCodeDecoder
import com.btrace.viewer.parser.decoders.TargetRefDecoder
import com.btrace.viewer.utils.CLogUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Singleton

/**
 * Parcel解析器
 * 从Parcel原始数据中提取接口名等信息
 *
 * spec § 6.3:[decodePipeline] 是主路径,把接口名探测拆成 8 档探测器。
 * [sniffArgumentTypes] 保留作为方法签名拿不到时的兜底,由 [EventRepository] 调用。
 */
/**
 * 注:本类由 [com.btrace.viewer.di.AppModule.provideParcelParser] 通过 `@Provides` 提供,
 * 不再标 `@Inject constructor` —— 因为 [transactionPairer] 有默认值方便单测无参构造,
 * 而 Hilt 不允许 `@Inject` 构造函数携带默认参数(会被识别成两个候选构造)。
 */
@Singleton
class ParcelParser constructor(
    private val transactionPairer: TransactionPairer = TransactionPairer(),
    // spec § 6.3 返回值结构化:ReplyDecoder 需要 MethodResolver 拿 returnType。
    // 默认 null 让无 Hilt 的单测可以零参构造,生产由 AppModule.provideParcelParser 注入。
    private val methodResolver: MethodResolver? = null,
    // 兜底档需要它把 raw target@0xN 反查到 owner 进程名。null 时 fallback 行为同旧版。
    private val handleResolver: BinderHandleResolver? = null,
) {

    companion object {
        private const val TAG = "ParcelParser"
        // AIDL 接口名理论上最长几百字节就到顶,定一个保守上限避免任何畸形数据触发巨量分配
        private const val MAX_INTERFACE_NAME_CHARS = 4096
    }

    // spec § 6.5:当前监控目标 uid。由 EventRepository.setTargetUid 同步进来,供
    // TransactionPairer 判断方向(client 表 vs server 表)。<= 0 时 pairer 全部 noop。
    @Volatile
    private var currentTargetUid: Int = 0

    fun setCurrentTargetUid(uid: Int) {
        currentTargetUid = uid
    }

    // 探测器顺序严格按 spec § 6.3.1:精确格式优先,启发式垫底,TargetRefDecoder 永远兜底。
    // ReplyDecoder 持有 pairer 引用,通过 lambda 拿当前 targetUid;其余 decoder 仍无状态。
    private val pipeline: List<ParcelDecoder> = listOf(
        ReplyDecoder(transactionPairer, { currentTargetUid }, methodResolver),
        SpecialCodeDecoder(),
        AidlQDecoder(),
        AidlPDecoder(),
        AidlODecoder(),
        HidlDescriptorDecoder(),
        RawAsciiHeuristicDecoder(),
        TargetRefDecoder(handleResolver)
    )

    /**
     * spec § 6.3.1 探测器流水线主入口。每个 decoder 非破坏性 peek,首个非 null 即终止。
     * [TargetRefDecoder] 永远兜底,所以这个函数永远不抛 [IllegalStateException]。
     *
     * 单个 decoder 抛异常时,流水线吞掉错误继续走下一档(以"事件不丢"为最高优先级)。
     */
    fun decodePipeline(event: BinderEvent): DecodeResult {
        for (decoder in pipeline) {
            try {
                decoder.tryDecode(event)?.let { return it }
            } catch (t: Throwable) {
                CLogUtils.w(TAG, "decoder ${decoder::class.simpleName} 抛异常,跳过: ${t.message}")
            }
        }
        error("TargetRefDecoder 必须兜底,流水线不应该走到这里")
    }

    /** 请求解析完成后再记录,让回复能拿到接口、方法和候选包。 */
    fun recordParsedRequest(event: BinderEvent) {
        transactionPairer.recordRequest(event, currentTargetUid)
    }

    /**
     * 启发式嗅探 Parcel payload 中的参数类型,作为 MethodResolver 拿不到方法签名时的兜底。
     *
     * 当方法名拿不到(壳保护、Vendor token 如 "Xiaomi"、第三方未导出 AIDL 等),
     * 仅靠 `code=N` 用户毫无线索;用 Parcel 自带的 magic 反推一个粗签名 (String, IBinder)
     * 之类,虽不精确,但比裸 code 强一个量级。
     *
     * 识别策略(从精确到模糊,4 字节对齐前进):
     *   1. flat_binder_object magic ('sb*\x85' / 'sh*\x85' / 'fd*\x85') —— 跳 24 字节
     *   2. UTF-16 字符串(int32 length + chars + NUL + 4 字节对齐),内容启发式校验
     *   3. -1 (常见 null marker)
     *   4. 默认按 int 处理
     *
     * 不区分 int/long(64 位字段会被读成两个 int)、不解析 List/array/Bundle 这类
     * 复合结构(它们走 spec § 9.3 的精确解码路径,不在兜底范围)。
     */
    fun sniffArgumentTypes(data: ByteArray, maxArgs: Int = 6): List<String> {
        if (data.size < 16) return emptyList()
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // 跳过 12 字节 strict-mode/work-source header
        buffer.position(12)
        val nameLength = buffer.int
        if (nameLength <= 0 || nameLength > MAX_INTERFACE_NAME_CHARS) return emptyList()

        // 跳过 InterfaceToken:UTF-16 (N+1 chars 含 NUL),最后对齐到 4 字节
        val tokenBytes = (nameLength + 1) * 2
        val tokenBytesPadded = (tokenBytes + 3) and 0x3.inv()
        val argStart = 16L + tokenBytesPadded
        if (argStart >= data.size) return emptyList()

        return sniffArgsFrom(data, argStart.toInt(), maxArgs)
    }

    private fun sniffArgsFrom(data: ByteArray, start: Int, maxArgs: Int): List<String> {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(start)
        val out = ArrayList<String>(maxArgs)

        while (buffer.remaining() >= 4 && out.size < maxArgs) {
            val pos = buffer.position()
            val word = buffer.getInt(pos)

            // BINDER_TYPE_BINDER='sb*\x85' 在 LE int32 读出来 = 0x852a6273
            // BINDER_TYPE_HANDLE='sh*\x85' = 0x852a6873
            // BINDER_TYPE_FD    ='fd*\x85' = 0x852a6466
            val typeName = when (word) {
                0x852a6273.toInt(), 0x852a6873.toInt() -> "IBinder"
                0x852a6466.toInt() -> "FileDescriptor"
                else -> null
            }
            if (typeName != null) {
                if (buffer.remaining() < 24) break
                buffer.position(pos + 24)
                out.add(typeName)
                continue
            }

            // null marker
            if (word == -1) {
                buffer.position(pos + 4)
                out.add("?")
                continue
            }

            // 尝试 String:length + (length+1)*2 字节(含 NUL),对齐到 4
            if (word in 1..MAX_INTERFACE_NAME_CHARS) {
                val strBytes = (word + 1) * 2
                val padded = (strBytes + 3) and 0x3.inv()
                val expectedEnd = pos + 4 + padded
                if (expectedEnd <= data.size) {
                    val sample = minOf(word, 8)
                    var readable = 0
                    for (i in 0 until sample) {
                        val ch = buffer.getShort(pos + 4 + i * 2).toInt() and 0xFFFF
                        // 可见 ASCII / 常用中日韩
                        if (ch in 0x20..0x7E || ch in 0x4E00..0x9FFF || ch in 0x3000..0x303F) {
                            readable++
                        }
                    }
                    if (sample > 0 && readable.toFloat() / sample >= 0.75f) {
                        buffer.position(expectedEnd)
                        out.add("String")
                        continue
                    }
                }
            }

            // 兜底:当 int(也覆盖 boolean/float;long/double 会被拆成两段,用户能感知近似)
            buffer.position(pos + 4)
            out.add("int")
        }
        return out
    }

    /**
     * 格式化Parcel数据为十六进制字符串
     */
    fun formatHex(data: ByteArray, bytesPerLine: Int = 16): String {
        CLogUtils.v(TAG, "formatHex() dataSize=${data.size}, bytesPerLine=$bytesPerLine")
        
        if (data.isEmpty()) {
            CLogUtils.v(TAG, "formatHex() 空数据")
            return ""
        }
        
        val sb = StringBuilder()
        var offset = 0
        
        while (offset < data.size) {
            // 地址
            sb.append(String.format("%08X: ", offset))
            
            // 十六进制值
            val lineEnd = minOf(offset + bytesPerLine, data.size)
            for (i in offset until lineEnd) {
                sb.append(String.format("%02X ", data[i].toInt() and 0xFF))
            }
            
            // 填充不足的部分
            for (i in lineEnd until offset + bytesPerLine) {
                sb.append("   ")
            }
            
            // ASCII表示
            sb.append(" |")
            for (i in offset until lineEnd) {
                val b = data[i].toInt() and 0xFF
                sb.append(if (b in 0x20..0x7E) b.toChar() else '.')
            }
            sb.append("|\n")
            
            offset += bytesPerLine
        }
        
        val result = sb.toString()
        CLogUtils.v(TAG, "formatHex() 格式化完成, 输出长度=${result.length}")
        return result
    }

}
