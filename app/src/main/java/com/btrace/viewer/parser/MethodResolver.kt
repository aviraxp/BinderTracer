package com.btrace.viewer.parser

import com.btrace.viewer.utils.CLogUtils
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 方法签名。methodName 优先来自 $Stub.getDefaultTransactionName(code),不可用时来自
 * $Stub 的 TRANSACTION_xxx 常量;paramTypes 来自接口类反射,
 * 每一项是参数的 canonical Java 类型名(例如 `java.lang.String`、`android.os.Bundle`、`int[]`、
 * `java.util.List<java.lang.String>`)。returnType 同样来自反射(`Method.getGenericReturnType()`),
 * 后续 P3-B 用来做 reply 返回值结构化解码。
 *
 * paramTypes 为空 = 已知方法名但接口类不可见(或无参)。
 * returnType = null = 反射拿不到 Method(参数类型不在当前 ClassLoader / 接口类不可见 / 等等),
 *   不影响 paramTypes 的填充。
 * null signature = 连方法名都没解出来。
 */
data class MethodSignature(
    val methodName: String,
    val paramTypes: List<String>,
    val returnType: String?,
)

/**
 * 方法名解析器
 *
 * 解析顺序:
 *   1. 反射 AIDL 生成的 `$Stub` 类,枚举 `public static final int TRANSACTION_* = N`
 *      常量,优先通过 `getDefaultTransactionName(int)` 还原正式方法名 ——
 *      动态、完整、始终和当前设备 Android 版本一致。
 *   2. 兜底查询 assets/methods.json —— 对 `$Stub` 不在 classpath / 被 HiddenApi
 *      拦截 / 非 AIDL 接口的情况提供静态映射。
 *   3. 都找不到返回 "code=N"。
 *
 * 反射走 non-SDK 接口,需配合 [BTraceApp.onCreate] 中的 HiddenApiBypass 豁免使用。
 *
 * spec 2026-05-03 § 3:[getMethodSignature] 入口接收候选包列表,按"被监控 App →
 * 接口索引命中包 → sender 包"顺序遍历。三级评分:
 *   - STRONG(methodName 非 null + paramTypes 非 empty)→ 立即短路返回
 *   - WEAK(methodName 非 null + paramTypes empty)→ 暂存 bestWeak,继续遍历
 *   - MISS(methodName 为 null)→ 写 [negativeCache] 60s TTL,继续下一候选
 */
@Singleton
class MethodResolver @Inject constructor(
    private val appClassLoaderRegistry: AppClassLoaderRegistry,
    private val staticMethodTable: StaticMethodTable,
    private val interfaceIndex: InterfaceIndex,
    /**
     * 内存层签名 LRU 缓存实例。spec § 4.4:容量上限走 BuildConfig.SIG_CACHE_MAX_ENTRIES
     * 暴露,DI 在 [com.btrace.viewer.di.AppModule.provideMethodResolver] 注入实例。
     * 单测路径用默认无参构造(默认 256)。
     */
    private val signatureCache: SignatureCache = SignatureCache(),
) {
    companion object {
        private const val TAG = "MethodResolver"
        private const val TRANSACTION_PREFIX = "TRANSACTION_"

        /** spec § 3.4 negativeCache 容量上限(LinkedHashMap LRU)。 */
        const val NEGATIVE_CACHE_MAX: Int = 1024
        /** spec § 3.4 negativeCache TTL = 60s。 */
        const val NEGATIVE_CACHE_TTL_MS: Long = 60_000L
    }

    // 反射结果缓存:interfaceName -> (code -> methodName)
    // ConcurrentHashMap 保证并发安全,无需额外锁。
    private val reflectionCache = ConcurrentHashMap<String, Map<Int, String>>()

    // 持久缓存(可选注入)。spec § 4.3:有 cache 时入口先查持久缓存。
    @Volatile
    private var persistentCache: PersistentSignatureCache? = null

    private data class NegKey(val packageName: String, val interfaceName: String)

    private val negativeLock = Any()

    private val negativeCache = object : LinkedHashMap<NegKey, Long>(
        16, 0.75f, /* accessOrder = */ true,
    ) {}

    /**
     * 注入持久缓存(避免循环依赖,DI 在构造完毕后调一次)。
     */
    fun attachPersistentCache(cache: PersistentSignatureCache?) {
        this.persistentCache = cache
    }

    /**
     * 仅供单测断言:negativeCache 当前大小。
     */
    internal fun negativeCacheSize(): Int = synchronized(negativeLock) { negativeCache.size }

    /** 仅供 [SignatureCache] 上限暴露(default = SignatureCache.DEFAULT_MAX_ENTRIES)。 */
    internal fun signatureCacheSize(): Int = signatureCache.size()

    /**
     * 获取方法名,未命中返回 "code=N"。
     *
     * 解析顺序:
     *   1. 系统 classloader 反射(命中 framework AIDL,例如 `android.app.IActivityManager`)
     *   2. 调用方 App 的 DexClassLoader(由 [AppClassLoaderRegistry] 提供,
     *      命中第三方私有 AIDL,例如微信的 `IReqResp_AIDL`)
     *   3. APK 级"接口名 → 包名"反查目录([InterfaceIndex],spec § 6.4 第 1 档):
     *      接口的 $Stub 不在 caller 包里也不在系统里时,按全局索引找候选 App 反射
     *   4. assets/methods.json 兜底
     *   5. `code=N`
     *
     * @param callerPackage 调用方包名,用于查目标 App 自己的 ClassLoader。
     *                       传 null 时跳过第 2 步。
     */
    fun getMethodName(interfaceName: String?, code: Int, callerPackage: String? = null): String {
        if (interfaceName == null) {
            return "code=$code"
        }

        // 1. 系统反射(framework AIDL),空结果不缓存:首次拉取空 map 不会锁死后续请求
        val reflected = reflectionCache[interfaceName] ?: run {
            val loaded = loadStubTransactions(interfaceName)
            if (loaded.isNotEmpty()) reflectionCache[interfaceName] = loaded
            loaded
        }
        reflected[code]?.let {
            CLogUtils.v(TAG, "getMethodName() 系统反射命中: $interfaceName#$it (code=$code)")
            return it
        }

        // 2. 调用方 App 的 DexClassLoader(第三方私有 AIDL)
        if (callerPackage != null) {
            appClassLoaderRegistry.getStubMap(callerPackage, interfaceName)?.get(code)?.let {
                CLogUtils.v(TAG, "getMethodName() App反射命中: $callerPackage / $interfaceName#$it (code=$code)")
                return it
            }
        }

        // 3. APK 级反查目录(spec § 6.4 第 1 档):接口名命中索引时,逐个候选 App 反射 $Stub
        for (candidatePkg in interfaceIndex.lookup(interfaceName)) {
            if (candidatePkg == callerPackage) continue   // 第 2 档已经查过
            appClassLoaderRegistry.prepareAsync(candidatePkg)
            appClassLoaderRegistry.getStubMap(candidatePkg, interfaceName)?.get(code)?.let {
                CLogUtils.v(
                    TAG,
                    "getMethodName() 索引命中: $candidatePkg / $interfaceName#$it (code=$code)",
                )
                return it
            }
        }

        // 4. assets/methods.json 静态补丁表(spec § 6.4 第 2 档,含 HIDL/IBase 等无 $Stub 接口)
        staticMethodTable.lookup(interfaceName, code)?.let {
            CLogUtils.v(TAG, "getMethodName() 静态表命中: $interfaceName#$it (code=$code)")
            return it
        }

        CLogUtils.v(TAG, "getMethodName() 未命中: $interfaceName code=$code (caller=$callerPackage)")
        return "code=$code"
    }

    /**
     * spec 2026-05-03 § 3.3 / § 3.4:按候选包列表三级评分遍历。
     *
     * 候选包顺序由 [com.btrace.viewer.data.EventRepository.parseEvent] 一次性算好(候选 1
     * 被监控 App → 候选 2 InterfaceIndex 命中 → 候选 3 sender 包,去重)。
     *
     * 命中策略:
     *   - STRONG(methodName 非 null + paramTypes 非 empty)→ 立即返回
     *   - WEAK(methodName 非 null + paramTypes empty)→ 暂存 bestWeak,继续遍历
     *   - MISS(methodName 为 null)→ 写 negativeCache,继续遍历
     * 全部候选遍历完仍无 STRONG → 返回 bestWeak(可能为 null,最差等于现状)。
     *
     * @return null 当所有候选都未解出 methodName(走 "code=N" 兜底路径时)。
     */
    @JvmOverloads
    fun getMethodSignature(
        interfaceName: String?,
        code: Int,
        candidates: List<String> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
    ): MethodSignature? {
        if (interfaceName == null) return null

        // 候选为空 → 退化:framework loader 路径(callerPackage = null)
        if (candidates.isEmpty()) {
            return resolveSingleCandidate(interfaceName, code, callerPackage = null, nowMs = nowMs)
                ?.takeIf { it.classify() != Classification.MISS }
                ?: run {
                    // 即使 MISS,只要 methodName 解到了仍要返回 WEAK -- 但此处 candidates 为空意味着
                    // 没有 callerPackage,getMethodName 仍可能命中系统反射 / methods.json,这种情况
                    // 已经由 resolveSingleCandidate 识别为 STRONG/WEAK 返回。MISS = 真无名字。
                    null
                }
        }

        var bestWeak: MethodSignature? = null
        for (pkg in candidates) {
            // negativeCache 命中且未过期 → 跳过
            if (isNegativeCached(pkg, interfaceName, nowMs)) {
                CLogUtils.v(TAG, "getMethodSignature() negativeCache 命中,跳过 pkg=$pkg iface=$interfaceName")
                continue
            }

            val sig = resolveSingleCandidate(interfaceName, code, callerPackage = pkg, nowMs = nowMs)
            when (sig?.classify()) {
                Classification.STRONG -> {
                    CLogUtils.v(TAG, "getMethodSignature() STRONG 短路 pkg=$pkg iface=$interfaceName method=${sig.methodName}")
                    return sig
                }
                Classification.WEAK -> {
                    if (bestWeak == null) {
                        bestWeak = sig
                        CLogUtils.v(TAG, "getMethodSignature() WEAK 暂存 pkg=$pkg iface=$interfaceName method=${sig.methodName}")
                    }
                }
                Classification.MISS, null -> {
                    putNegativeCache(pkg, interfaceName, nowMs)
                }
            }
        }
        return bestWeak
    }

    /**
     * 在单一候选 [callerPackage] 下试图解析:返回完整 MethodSignature(可能 paramTypes 为空)
     * 或 null(连 methodName 都没解到)。
     *
     * spec § 4.3 入口:有持久缓存且版本一致时,直接灌内存 + 短路返回 STRONG/WEAK。
     * 版本失配则发 Invalidate op。
     */
    private fun resolveSingleCandidate(
        interfaceName: String,
        code: Int,
        callerPackage: String?,
        @Suppress("UNUSED_PARAMETER") nowMs: Long,
    ): MethodSignature? {
        // 1) 先解 methodName(系统反射 / caller ClassLoader / 索引 / methods.json / "code=N")
        val methodName = getMethodName(interfaceName, code, callerPackage)
        if (methodName.startsWith("code=")) {
            // MISS:连方法名都没,签名无从谈起
            return null
        }

        // 2) 解 paramTypes / returnType:先试持久缓存 → 内存缓存 → 反射
        // spec § 4.3:命中且版本一致 → 灌内存 + 短路;版本失配 → 发 Invalidate op + 走反射
        if (callerPackage != null) {
            val pcache = persistentCache
            if (pcache != null) {
                val meta = appClassLoaderRegistry.getPackageMeta(callerPackage)
                if (meta != null) {
                    val cached = pcache.get(callerPackage, interfaceName, meta.versionCode, meta.lastUpdateTime)
                    if (cached != null && cached.isNotEmpty()) {
                        // 持久缓存命中 → 灌内存,返回对应 method 签名
                        for ((_, s) in cached) signatureCache.put(callerPackage, interfaceName, s)
                        return cached[methodName] ?: MethodSignature(methodName, emptyList(), null)
                    }
                    // 命中失败可能有两种:整个包没记录,或版本失配。peek (不带版本校验) 区分:
                    val raw = pcache.peekRaw(callerPackage, interfaceName)
                    if (raw != null && raw.isNotEmpty()) {
                        // 有旧记录但版本失配 → 发 Invalidate op,actor 串行清掉整包
                        pcache.invalidate(callerPackage)
                    }
                }
            }
        }

        val sigMap = signatureCache.getOrLoad(callerPackage, interfaceName) { _, _ ->
            loadInterfaceSignatures(interfaceName, callerPackage)
        }

        // 3) 写持久缓存(带版本元数据,严禁直接调 PM)
        if (sigMap.isNotEmpty() && callerPackage != null) {
            val pcache = persistentCache
            if (pcache != null) {
                val meta = appClassLoaderRegistry.getPackageMeta(callerPackage)
                if (meta != null) {
                    pcache.put(callerPackage, interfaceName, meta.versionCode, meta.lastUpdateTime, sigMap)
                } else {
                    CLogUtils.w(
                        TAG,
                        "telemetry cache=signature_disk level=entry reason=missing_meta " +
                            "pkg=$callerPackage iface=$interfaceName entryCount=${sigMap.size} " +
                            "bytes=null maxEntries=${SignatureCache.DEFAULT_MAX_ENTRIES} " +
                            "maxPackages=${PersistentSignatureCache.MAX_PACKAGES} " +
                            "maxBytesPerPackage=${PersistentSignatureCache.MAX_BYTES_PER_PACKAGE} " +
                            "totalSoftLimitBytes=${PersistentSignatureCache.TOTAL_SOFT_LIMIT_BYTES}",
                    )
                }
            }
        }

        return sigMap[methodName] ?: MethodSignature(methodName, emptyList(), null)
    }

    private enum class Classification { STRONG, WEAK, MISS }

    private fun MethodSignature.classify(): Classification = when {
        methodName.startsWith("code=") -> Classification.MISS
        paramTypes.isNotEmpty() -> Classification.STRONG
        else -> Classification.WEAK
    }

    private fun isNegativeCached(pkg: String, iface: String, nowMs: Long): Boolean {
        synchronized(negativeLock) {
            val k = NegKey(pkg, iface)
            val ts = negativeCache[k] ?: return false
            if (nowMs - ts > NEGATIVE_CACHE_TTL_MS) {
                negativeCache.remove(k)
                return false
            }
            return true
        }
    }

    private fun putNegativeCache(pkg: String, iface: String, nowMs: Long) {
        synchronized(negativeLock) {
            negativeCache[NegKey(pkg, iface)] = nowMs
            while (negativeCache.size > NEGATIVE_CACHE_MAX) {
                val it = negativeCache.entries.iterator()
                if (!it.hasNext()) break
                it.next(); it.remove()
            }
        }
    }

    /**
     * 反射接口类,列出所有 public 方法的参数类型 + 返回类型。
     *
     * 注意:AIDL `$Stub` 有两个同名方法的 method —— 一个是接口上继承的,一个是 $Stub 上生成的
     * `onTransact` dispatch。我们反射的是 Interface 本身(不是 $Stub),Method 实例只有接口声明的那个。
     *
     * 对系统接口(`android.app.IActivityManager`),Class.forName 走应用的系统 classloader;
     * 对 App 私有接口,后续可通过 [AppClassLoaderRegistry] 的 classloader 再补一层 —— 但参数
     * 类型里可能包含 App 自己的 Parcelable,反射只需类型名,不需要 instantiate,所以
     * 系统 classloader 通常就够了。
     *
     * 单个方法的 paramTypes / returnType 各自独立处理:某个 method 取 returnType 抛 TypeNotPresentException
     * 等异常时,returnType 退化成 null,paramTypes 仍正常返回(spec § 6.4 第 2 档)。
     */
    private fun loadInterfaceSignatures(
        interfaceName: String,
        callerPackage: String?,
    ): Map<String, MethodSignature> {
        val loaders = buildList<(String) -> Class<*>?> {
            add { Class.forName(it) }
            if (callerPackage != null) {
                add { appClassLoaderRegistry.loadClass(callerPackage, it) }
            }
            // 第 4 档:索引命中的候选包也参与签名反射,顺序在 caller 之后
            for (candidatePkg in interfaceIndex.lookup(interfaceName)) {
                if (candidatePkg == callerPackage) continue
                add { appClassLoaderRegistry.loadClass(candidatePkg, it) }
            }
        }

        for (loader in loaders) {
            val cls = try { loader(interfaceName) } catch (_: Throwable) { null } ?: continue
            val result = HashMap<String, MethodSignature>()
            try {
                for (m in cls.methods) {
                    if (result.containsKey(m.name)) continue
                    val params = try {
                        m.genericParameterTypes.map { formatTypeName(it) }
                    } catch (t: Throwable) {
                        CLogUtils.v(TAG, "loadInterfaceSignatures() $interfaceName.${m.name} paramTypes 反射失败: ${t.message}")
                        emptyList()
                    }
                    val returnType = try {
                        formatTypeName(m.genericReturnType)
                    } catch (t: Throwable) {
                        CLogUtils.v(TAG, "loadInterfaceSignatures() $interfaceName.${m.name} returnType 反射失败: ${t.message}")
                        null
                    }
                    result[m.name] = MethodSignature(m.name, params, returnType)
                }
                if (result.isNotEmpty()) {
                    CLogUtils.d(TAG, "loadInterfaceSignatures() $interfaceName → ${result.size} 个方法签名")
                    return result
                }
            } catch (t: Throwable) {
                CLogUtils.v(TAG, "loadInterfaceSignatures() $interfaceName 反射方法失败: ${t.message}")
            }
        }
        return emptyMap()
    }

    private fun formatTypeName(type: java.lang.reflect.Type): String {
        return when (type) {
            is Class<*> -> when {
                type.isArray -> {
                    val comp = type.componentType ?: return "unknown[]"
                    formatTypeName(comp) + "[]"
                }
                type.isPrimitive -> type.simpleName
                else -> type.name.substringAfterLast('.')
            }
            is ParameterizedType -> {
                val raw = formatTypeName(type.rawType)
                val args = type.actualTypeArguments.joinToString(",") { formatTypeName(it) }
                "$raw<$args>"
            }
            else -> type.toString().substringAfterLast('.')
        }
    }

    private fun loadStubTransactions(interfaceName: String): Map<Int, String> {
        return try {
            val stubCls = Class.forName("$interfaceName\$Stub")
            val getDefaultName = try {
                stubCls.getDeclaredMethod("getDefaultTransactionName", Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
            } catch (_: Throwable) {
                null
            }
            val result = HashMap<Int, String>()
            for (field in stubCls.declaredFields) {
                if (!Modifier.isStatic(field.modifiers)) continue
                if (field.type != Int::class.javaPrimitiveType) continue
                val name = field.name
                if (!name.startsWith(TRANSACTION_PREFIX)) continue

                try {
                    field.isAccessible = true
                    val code = field.getInt(null)
                    val methodName = if (getDefaultName != null) {
                        try {
                            getDefaultName.invoke(null, code) as? String
                        } catch (_: Throwable) {
                            null
                        }
                    } else {
                        null
                    }?.takeIf { it.isNotEmpty() }
                        ?: name.removePrefix(TRANSACTION_PREFIX)
                    result[code] = methodName
                } catch (t: Throwable) {
                    CLogUtils.v(TAG, "loadStubTransactions() 跳过字段 $interfaceName.$name: ${t.message}")
                }
            }
            if (result.isNotEmpty()) {
                CLogUtils.d(TAG, "loadStubTransactions() $interfaceName 反射得到 ${result.size} 个方法")
            }
            result
        } catch (e: ClassNotFoundException) {
            CLogUtils.v(TAG, "loadStubTransactions() $interfaceName\$Stub 找不到,用兜底表")
            emptyMap()
        } catch (t: Throwable) {
            CLogUtils.w(TAG, "loadStubTransactions() $interfaceName 反射失败: ${t.message}", t)
            emptyMap()
        }
    }

    fun invalidate(interfaceName: String) {
        reflectionCache.remove(interfaceName)
    }

    fun invalidatePackage(packageName: String) {
        signatureCache.invalidatePackage(packageName)
    }
}
