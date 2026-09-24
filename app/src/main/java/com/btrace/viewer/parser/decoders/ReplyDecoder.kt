package com.btrace.viewer.parser.decoders

import com.btrace.viewer.model.BinderDev
import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.parser.MethodResolver
import com.btrace.viewer.parser.ReplyParser
import com.btrace.viewer.parser.TransactionPairer

/**
 * spec § 6.3.1 第 0 档 + § 6.3.2 + § 6.3 返回值结构化:
 *
 * reply 帧不写 interfaceToken,直接拿当请求解会得到无意义的 "Unknown"。三种命中情况:
 *   1. `pair_id != 0` 且 [TransactionPairer.tryMatchReply] 命中 → 继承请求侧 interfaceName /
 *      methodName,接着用 [MethodResolver.getMethodSignature] 拿 returnType,经
 *      [ReplyParser.decodeJavaAidlReply] 跳过 4B 异常头并按 returnType 解一层返回值
 *      / 异常类名,结果挂到 [BinderEvent.parsedReply],methodHint 升级为
 *      `<method> ← reply = <value>` 或 `<method> ← reply (exception: <Class>)`
 *   2. `pair_id != 0` 但配对未命中(超时被 evict / 监控前已在飞)→ `← reply` + `(reply)`
 *   3. `pair_id == 0`(daemon 未提供配对 ID 的设备)→ `← reply (orphan)`
 *
 * [methodResolver] 可为 null —— 旧测试 / Pipeline 测试不需要返回值结构化时直接退化为
 * 仅继承 interface/method 的旧行为。生产路径在 [com.btrace.viewer.di.AppModule] 注入。
 *
 * [targetUidProvider] 由 ParcelParser 注入,跟随 EventRepository.setTargetUid 动态变化;
 * EventRepository 在请求解析完成后记录配对信息。
 */
class ReplyDecoder(
    private val pairer: TransactionPairer? = null,
    private val targetUidProvider: () -> Int = { 0 },
    private val methodResolver: MethodResolver? = null,
) : ParcelDecoder {

    override fun tryDecode(event: BinderEvent): DecodeResult? {
        if (!event.isReply) return null

        // 优先尝试配对继承
        if (event.pairId != 0L && pairer != null) {
            val matched = pairer.tryMatchReply(event, targetUidProvider())
            if (matched != null) {
                return decodeWithInheritance(event, matched)
            }
        }

        // pair 未命中(orphan / sideband race / pair_id=0)时仍尝试解 parcel:
        //   - returnType 未知 → ReplyParser 走 raw hex 兜底,但**异常头**(EX_SECURITY 等
        //     已知码)仍能解出,UI 至少能告诉用户"是不是异常 + 前 32B hex 摘要",
        //     而不是空白。
        //   - ReplyParser 自身已有完整 fail-open(任何字段读不到都退到 raw hex),
        //     这里再 catch 一层兜底,保证 ReplyDecoder.tryDecode 在异常下不丢事件。
        //
        // **HIDL/vndbinder 风险隔离**:HIDL reply 前 4B 是 `_hidl_status`,而不是
        // Java AIDL 异常码。若 status 落在 EX_*(-1/-2/-3/-4/-5/-7/-8/-9/-127/-128)
        // 名单内,会被 ReplyParser 误标成 `SecurityException` / `IllegalArgumentException`
        // 等假阳性。所以这里只对 `binderDev == BINDER` 走异常头解析;HIDL/VNDBINDER/
        // UNKNOWN 仅给 raw hex 摘要,语义安全。
        val replyResult = try {
            if (event.binderDev == BinderDev.BINDER) {
                ReplyParser.decodeJavaAidlReply(event.rawParcel, returnType = null)
            } else {
                ReplyParser.ReplyDecodeResult(
                    exception = null,
                    value = null,
                    rawHexHint = formatRawHexHint(event.rawParcel),
                )
            }
        } catch (_: Throwable) {
            null
        }
        if (replyResult != null) {
            event.parsedReply = replyResult
        }

        val hint = if (event.pairId == 0L) "← reply (orphan)" else "← reply"
        return DecodeResult(
            interfaceName = "(reply)",
            methodHint = hint,
            payloadStart = -1,
            source = DecodeSource.REPLY,
            confidence = Confidence.HIGH
        )
    }

    /**
     * pairer 命中后:继承 interface/method,再(若可能)结构化解码返回值 / 异常,把结果
     * 同时写到 event.parsedReply(供详情页)和 methodHint(供列表行尾标签)。
     *
     * methodResolver / interfaceName / methodName 任何一环为空 → 自然降级到旧的"仅继承"
     * 行为,methodHint 仍是 `<method> (reply)`。
     */
    private fun decodeWithInheritance(
        event: BinderEvent,
        matched: TransactionPairer.PairResult
    ): DecodeResult {
        val interfaceName = matched.interfaceName ?: "(reply)"
        val baseMethod = matched.methodName

        // 没有 methodResolver / 接口名缺失:维持旧行为,不解返回值
        if (methodResolver == null || matched.interfaceName == null) {
            return DecodeResult(
                interfaceName = interfaceName,
                methodHint = baseMethod?.let { "$it (reply)" } ?: "← reply (matched)",
                payloadStart = -1,
                source = DecodeSource.REPLY,
                confidence = Confidence.HIGH,
            )
        }

        val isJavaAidl = event.binderDev == BinderDev.BINDER && when (matched.decodeSource) {
            DecodeSource.AIDL_Q, DecodeSource.AIDL_P, DecodeSource.AIDL_O -> true
            else -> false
        }
        // reply 的 code 和发送方不代表原始调用,查签名必须用请求的信息。
        val signature = try {
            if (isJavaAidl) methodResolver.getMethodSignature(
                matched.interfaceName, matched.code, matched.resolveCandidates,
            ) else null
        } catch (_: Throwable) {
            null
        }
        val returnType = signature?.returnType

        val replyResult = try {
            if (isJavaAidl) {
                ReplyParser.decodeJavaAidlReply(event.rawParcel, returnType)
            } else {
                ReplyParser.ReplyDecodeResult(null, null, formatRawHexHint(event.rawParcel))
            }
        } catch (_: Throwable) {
            null
        }

        if (replyResult != null) {
            event.parsedReply = replyResult
        }

        val methodLabel = baseMethod ?: "← reply"
        val (hint, confidence) = when {
            replyResult?.exception != null ->
                "$methodLabel ← reply (exception: ${replyResult.exception})" to Confidence.MEDIUM
            replyResult?.value != null ->
                "$methodLabel ← reply = ${replyResult.value}" to Confidence.HIGH
            baseMethod != null ->
                "$baseMethod (reply)" to Confidence.HIGH
            else ->
                "← reply (matched)" to Confidence.HIGH
        }

        return DecodeResult(
            interfaceName = interfaceName,
            methodHint = hint,
            payloadStart = -1,
            source = DecodeSource.REPLY,
            confidence = confidence,
        )
    }

    /**
     * 取 parcel 前 32 字节的紧凑 hex 摘要(`AB CD EF …`),供 HIDL/VNDBINDER 等
     * 非 Java AIDL reply 路径直接挂 [BinderEvent.parsedReply.rawHexHint],绕过
     * Java 异常头解析。语义与 [ReplyParser] 内部 rawHexHint 一致,保持简短防止
     * 详情页被长 hex 撑爆。
     */
    private fun formatRawHexHint(parcel: ByteArray): String {
        if (parcel.isEmpty()) return ""
        val limit = minOf(parcel.size, RAW_HEX_HINT_BYTES)
        val sb = StringBuilder(limit * 3)
        for (i in 0 until limit) {
            if (i > 0) sb.append(' ')
            sb.append(String.format("%02X", parcel[i].toInt() and 0xFF))
        }
        if (parcel.size > limit) sb.append(" …")
        return sb.toString()
    }

    companion object {
        private const val RAW_HEX_HINT_BYTES = 32
    }
}
