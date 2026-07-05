package com.btrace.viewer.parser

import android.content.Context
import android.content.pm.PackageManager
import com.btrace.viewer.root.RootManager
import com.btrace.viewer.utils.CLogUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import dalvik.system.DexClassLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 目标 App 的 ClassLoader 注册表
 *
 * 用途:第三方 App 的 AIDL `$Stub` 类不在 BTrace Viewer 的 classpath 里,
 * 系统反射读不到它们的 `getDefaultTransactionName(int)` / `TRANSACTION_*` 映射。本类做一次:
 *   1) 用 root 把目标 App 的 base.apk 复制到本 App 的私有目录,chown 到本 App uid;
 *   2) 用 DexClassLoader 加载,parent 指向本 App 的 classloader(framework 类走 bootclasspath);
 *   3) 缓存 ClassLoader,以及 `(packageName, interfaceName) → code→name` 映射。
 *
 * 整个过程对目标 App 完全无感(只读它的安装包,不注入、不 hook)。
 *
 * spec 2026-05-03 § 4.5:失败的包记录在 [failedPackages]([FailedEntry]),按
 * [shouldRetry] 退避表(TRANSIENT 1h 封顶 / PERMANENT 24h 封顶)决定能否重试。
 * APK 升级(versionCode 或 lastUpdateTime 变化)立即重置 entry 并触发持久 / 内存
 * 缓存失效。
 *
 * spec 2026-05-03 § 4.2:**全 spec 调 PackageManager 拿版本元数据的位置只有两处**,
 * 都在本类内部:
 *   1. [buildClassLoader] 首次成功获取 ApplicationInfo 后顺手 [recordPackageMeta]
 *   2. [prepareAsync] 重试路径在 shouldRetry 触发时再调一次比对
 *
 * 调用方(MethodResolver / PersistentSignatureCache / EventRepository / ReplyDecoder)
 * **严禁**直接调 PackageManager 拿 versionCode / lastUpdateTime,统一走 [getPackageMeta]
 * 读取已 cache 的元数据。
 *
 * **PM 调用边界**(spec § 12 grep 校验 `PackageManager.*getPackageInfo` 单一来源):
 *   - **getPackageInfo** 只在本类内调(getPackageInfo 必读 versionCode/lastUpdateTime)
 *   - **getApplicationInfo** 在本类、[InterfaceIndexBuilder]、[com.btrace.viewer.data.AppRepository]
 *     都有调用,但**只读 sourceDir / ApplicationLabel**,不读版本字段,不破坏不变量。
 *     CR 时跑 `grep PackageManager.*getPackageInfo` 应只命中本类一处。
 */
@Singleton
open class AppClassLoaderRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootManager: RootManager,
) {
    companion object {
        private const val TAG = "AppClassLoaderRegistry"
        private const val DEX_DIR = "dex_cache"
        private const val TRANSACTION_PREFIX = "TRANSACTION_"

        /** spec § 4.5:首次失败退避基数 30 s,每次乘 2 直至上限。 */
        const val BACKOFF_BASE_MS: Long = 30_000L
        /** spec § 4.5:TRANSIENT 类(root / DexClassLoader 失败)封顶 1 h。 */
        const val BACKOFF_CAP_TRANSIENT_MS: Long = 3_600_000L
        /** spec § 4.5:PERMANENT 类(NameNotFound / sourceDir 缺失)封顶 24 h。 */
        const val BACKOFF_CAP_PERMANENT_MS: Long = 86_400_000L
        /** spec § 4.5:failedPackages 内存上限,LRU 淘汰最老 entry。 */
        const val FAILED_PACKAGES_MAX: Int = 256

        /**
         * 根据 [failCount] 计算下一个退避窗口(ms)。failCount 起始 1,封顶 1<<7。
         * 可见性:internal 给单测覆盖 8 个 step。
         */
        internal fun computeBackoffMs(failCount: Int, kind: ErrorKind): Long {
            val cap = if (kind == ErrorKind.PERMANENT) BACKOFF_CAP_PERMANENT_MS else BACKOFF_CAP_TRANSIENT_MS
            val shift = (failCount - 1).coerceIn(0, 7)
            return minOf(BACKOFF_BASE_MS shl shift, cap)
        }
    }

    /**
     * spec § 4.2 元数据 cache。`buildClassLoader` 与 `prepareAsync` 重试路径写入,
     * 调用方读取([getPackageMeta])。`PackageMeta(versionCode, lastUpdateTime)`。
     */
    data class PackageMeta(val versionCode: Int, val lastUpdateTime: Long)

    /**
     * spec § 4.5 失败原因分类。
     */
    enum class ErrorKind {
        /** Root 拷贝失败 / DexClassLoader 构造失败:可能临时,纳入退避序列(封顶 1h)。 */
        TRANSIENT,
        /** PackageManager.NameNotFoundException / 无 sourceDir:不可恢复,封顶 24h。 */
        PERMANENT,
    }

    /**
     * spec § 4.5 失败记录。
     *
     * - [firstFailAt] 首次失败时间(ms,System.currentTimeMillis)
     * - [lastFailAt] 最近一次失败时间
     * - [failCount] 累计失败次数(用于退避指数)
     * - [versionCode] / [lastUpdateTime] 失败时的 PM 元数据快照,用于 APK 升级检测
     * - [errorKind] 失败种类,决定退避封顶
     */
    data class FailedEntry(
        val firstFailAt: Long,
        val lastFailAt: Long,
        val failCount: Int,
        val versionCode: Int,
        val lastUpdateTime: Long,
        val errorKind: ErrorKind,
    )

    // 注:用 SupervisorJob 是为了让单个包的失败不传播到其它包的预热
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val classLoaders = ConcurrentHashMap<String, ClassLoader>()
    private val packageMeta = ConcurrentHashMap<String, PackageMeta>()
    private val stubMaps = ConcurrentHashMap<String, Map<Int, String>>() // key = "$pkg|$iface"

    // failedPackages: LinkedHashMap accessOrder=true,LRU evict by insertion+access order。
    // 所有写访问都加 [failedLock] 保证 LRU 维护原子。
    private val failedLock = Any()
    private val failedPackages = object : LinkedHashMap<String, FailedEntry>(
        16, 0.75f, /* accessOrder = */ true,
    ) {}

    private val buildMutex = Mutex()

    // 注入点:MethodResolver / 主流程在 APK 升级触发清缓存时调用。
    // 用 setter 注入而非构造注入是为了避免循环依赖(MethodResolver / PersistentSignatureCache
    // 都依赖本类 → 本类反过来依赖它们会绕)。
    @Volatile
    private var persistentCache: PersistentSignatureCache? = null
    @Volatile
    private var signatureInvalidator: ((String) -> Unit)? = null

    /**
     * 由 DI 在构造完毕后注入(避免循环依赖)。
     *
     * 注:本类持有的引用用于 APK 升级时主动清缓存。null 仍然安全,只是清缓存路径降级为
     * no-op,运行时通过版本号双因子兜底也能命中失效。
     */
    fun attachCacheInvalidators(
        persistentCache: PersistentSignatureCache?,
        signatureInvalidator: ((String) -> Unit)?,
    ) {
        this.persistentCache = persistentCache
        this.signatureInvalidator = signatureInvalidator
    }

    /**
     * 异步预热某个包的 ClassLoader。fire-and-forget,不阻塞调用方。
     * 在监控启动握手完成后调用,典型耗时 1~3s(取决于 APK 大小)。
     *
     * spec § 4.5:进入前先调 [shouldRetry] 决定是否仍在退避窗口内。
     *
     * @param onReady 预热完成(成功或失败)后回调一次。成功时 `success=true`,失败时 false。
     *                典型用法:成功后调 [MethodResolver.invalidatePackage] 失效该包下旧的空缓存。
     */
    fun prepareAsync(packageName: String, onReady: ((success: Boolean) -> Unit)? = null) {
        // spec § 4.5 + § 4.2:**入口先做 PM 比对再 short-circuit**,否则一旦 ClassLoader
        // 已经 ready,APK 升级路径永远走不到三件套清缓存(BLOCKER B1)。流程:
        //   1) 读 PM 元数据(NameNotFound → 记 PERMANENT 失败 + 返回)
        //   2) 与 packageMeta cache 比对;不一致 = APK 升级 → 三件套清缓存 +
        //      classLoaders.remove(pkg) 强制重新 build(旧 ClassLoader 引用的 dex 已过期)
        //   3) ClassLoader 已 ready → 短路返回 true
        //   4) 退避窗口内 → 返回 false
        //   5) 否则进 buildClassLoader
        val pmSnapshot = readPackageMetaFromPm(packageName)
        if (pmSnapshot == null) {
            // PM 直接 NameNotFoundException → 直接记 PERMANENT(若已记则保持原样)
            recordFailure(packageName, kind = ErrorKind.PERMANENT, ver = -1, mtime = -1L)
            onReady?.invoke(false)
            return
        }

        // 检测 APK 升级:若 packageMeta 已 cache 且与当前 PM 不一致 → 全清三件套并强制重 build。
        val cached = packageMeta[packageName]
        if (cached != null && cached != pmSnapshot) {
            CLogUtils.i(
                TAG,
                "prepareAsync($packageName) APK 升级 ${cached.versionCode}→${pmSnapshot.versionCode} " +
                    "/ ${cached.lastUpdateTime}→${pmSnapshot.lastUpdateTime},清缓存三件套",
            )
            packageMeta[packageName] = pmSnapshot
            persistentCache?.invalidate(packageName)
            signatureInvalidator?.invoke(packageName)
            // 旧 ClassLoader 还指着旧 dex,必须丢掉重建;同时清掉 stubMaps 里该包所有条目。
            classLoaders.remove(packageName)
            stubMaps.keys.removeAll { it.startsWith("$packageName|") }
            // 升级动作本身视为修复,清掉退避记录(若有),让本次允许立刻重 build。
            synchronized(failedLock) { failedPackages.remove(packageName) }
        }

        // ClassLoader 已 ready 且无升级 → 短路成功
        if (classLoaders.containsKey(packageName)) {
            onReady?.invoke(true)
            return
        }
        // 仍在退避窗口内 → 跳过本次重试
        if (!shouldRetry(packageName, pmSnapshot.versionCode, pmSnapshot.lastUpdateTime)) {
            onReady?.invoke(false)
            return
        }

        scope.launch {
            buildClassLoader(packageName)
            val ok = classLoaders.containsKey(packageName)
            onReady?.invoke(ok)
        }
    }

    /**
     * 当前已成功预热的包名集合(快照)。供 [InterfaceIndexBuilder] 决定要扫哪些已被监控的包。
     */
    fun monitoredPackages(): Set<String> = classLoaders.keys.toSet()

    /**
     * 用目标 App 的 ClassLoader 查一个类(供 spec § 9.2 的方法签名反射使用)。
     * ClassLoader 未 ready / 类不在 → 返回 null。
     */
    fun loadClass(packageName: String, className: String): Class<*>? {
        val cl = classLoaders[packageName] ?: return null
        return try {
            Class.forName(className, false, cl)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * spec § 4.2 元数据 O(1) 读取。已 cache 的 versionCode + lastUpdateTime,
     * 调用方(MethodResolver Put 时)用来携带元数据,**不**直接调 PackageManager。
     *
     * 返回 null 表示尚未在本类内部成功调 PM(buildClassLoader 未跑完 / NameNotFound)。
     */
    fun getPackageMeta(packageName: String): PackageMeta? = packageMeta[packageName]

    /**
     * 仅供单测使用:直接写入 packageMeta map,绕过 buildClassLoader 真实 PM 调用。
     * 用于验证 APK 升级三件套清缓存路径(没装这个包的测试环境无法走真 PM)。
     */
    @androidx.annotation.VisibleForTesting
    internal fun forceSetPackageMeta(packageName: String, meta: PackageMeta) {
        packageMeta[packageName] = meta
    }

    /**
     * 仅供单测使用:写入一个伪 ClassLoader,模拟 buildClassLoader 已经成功的状态,
     * 用于验证 prepareAsync 在 APK 升级时把它清掉(B1 路径)。
     */
    @androidx.annotation.VisibleForTesting
    internal fun forceSetClassLoaderForTest(packageName: String, classLoader: ClassLoader) {
        classLoaders[packageName] = classLoader
    }

    /**
     * 仅供单测使用:读取 classLoaders map 是否包含某 pkg(验证 APK 升级时被 remove)。
     */
    @androidx.annotation.VisibleForTesting
    internal fun isClassLoaderReadyForTest(packageName: String): Boolean =
        classLoaders.containsKey(packageName)

    /**
     * 仅供单测使用:直接调内部的 [recordPackageMeta],验证 defense-in-depth 路径
     * (M4 修复:即使绕过 prepareAsync 入口比对,只要进了 buildClassLoader 内的
     * recordPackageMeta,也能触发三件套清缓存)。
     */
    @androidx.annotation.VisibleForTesting
    internal fun recordPackageMetaForTest(packageName: String) {
        recordPackageMeta(packageName)
    }

    /**
     * spec § 4.5 退避决策。当前时间(ms)由 [now] 提供 —— 测试可注入虚拟时钟。
     *
     * - 包未失败过 → true
     * - APK 升级(versionCode 或 lastUpdateTime 变化)→ 立即重置 entry,返回 true
     * - 仍在退避窗口内 → false
     * - 超过窗口 → true(由 buildClassLoader 决定是否再次失败)
     */
    fun shouldRetry(
        packageName: String,
        currentVer: Int,
        currentMtime: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        synchronized(failedLock) {
            val e = failedPackages[packageName] ?: return true
            // APK 升级 → 立即重置(任何 errorKind 都重置,因为升级可能修复)
            if (e.versionCode != currentVer || e.lastUpdateTime != currentMtime) {
                failedPackages.remove(packageName)
                return true
            }
            val backoffMs = computeBackoffMs(e.failCount, e.errorKind)
            return now - e.lastFailAt >= backoffMs
        }
    }

    /**
     * 当前 [packageName] 是否记录在 failedPackages(供单测断言)。
     */
    fun isFailed(packageName: String): Boolean = synchronized(failedLock) {
        failedPackages.containsKey(packageName)
    }

    /**
     * 当前 failedPackages 大小(供单测断言 LRU)。
     */
    internal fun failedSize(): Int = synchronized(failedLock) { failedPackages.size }

    /**
     * 仅单测使用:读出 entry 内容做断言。
     */
    internal fun peekFailed(packageName: String): FailedEntry? = synchronized(failedLock) {
        failedPackages[packageName]
    }

    /**
     * 同步查询某 (包名, 接口名) 下的方法表。返回 null 表示:
     *   - ClassLoader 还没 ready(预热中或失败)
     *   - $Stub 类不在该包内
     *   - 反射没有抓到 TRANSACTION_* 字段
     *
     * 实现注意(review #2):**绝不缓存空结果**。预热中 / classloader 未 ready 时拿到的空 map
     * 一旦写进缓存,后续 ClassLoader 就绪后这条记录会永久把方法名锁在 `code=N`。
     */
    fun getStubMap(packageName: String, interfaceName: String): Map<Int, String>? {
        val key = "$packageName|$interfaceName"
        stubMaps[key]?.let { return it }   // 已缓存的一定非空

        val cl = classLoaders[packageName] ?: return null

        val map = try {
            val stubCls = Class.forName("$interfaceName\$Stub", false, cl)
            val getDefaultName = try {
                stubCls.getDeclaredMethod("getDefaultTransactionName", Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
            } catch (_: Throwable) {
                null
            }
            val out = HashMap<Int, String>()
            for (f in stubCls.declaredFields) {
                if (!Modifier.isStatic(f.modifiers)) continue
                if (f.type != Int::class.javaPrimitiveType) continue
                if (!f.name.startsWith(TRANSACTION_PREFIX)) continue
                try {
                    f.isAccessible = true
                    val code = f.getInt(null)
                    val methodName = if (getDefaultName != null) {
                        try {
                            getDefaultName.invoke(null, code) as? String
                        } catch (_: Throwable) {
                            null
                        }
                    } else {
                        null
                    }?.takeIf { it.isNotEmpty() }
                        ?: f.name.removePrefix(TRANSACTION_PREFIX)
                    out[code] = methodName
                } catch (t: Throwable) {
                    CLogUtils.v(TAG, "跳过字段 $packageName/$interfaceName.${f.name}: ${t.message}")
                }
            }
            if (out.isNotEmpty()) {
                CLogUtils.d(TAG, "动态命中 $packageName/$interfaceName,${out.size} 个方法")
            }
            out
        } catch (e: ClassNotFoundException) {
            CLogUtils.v(TAG, "$interfaceName\$Stub 不在 $packageName 的 classpath 内")
            emptyMap()
        } catch (t: Throwable) {
            CLogUtils.w(TAG, "反射 $interfaceName\$Stub 失败 ($packageName): ${t.message}")
            emptyMap()
        }

        if (map.isNotEmpty()) {
            stubMaps[key] = map
            return map
        }
        return null
    }

    /**
     * 读取当前 PM 中 [packageName] 的元数据(versionCode + lastUpdateTime)。
     * spec § 4.2:**全 spec 仅本类内两处会调 PackageManager 拿版本号**(本方法 + buildClassLoader)。
     *
     * 可见性 internal open + 类 open:仅供单测覆盖(spy.doReturn(...)) 来注入 PM 元数据,
     * 而无需在测试机上真的安装目标包。
     */
    internal open fun readPackageMetaFromPm(packageName: String): PackageMeta? = try {
        val pi = context.packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        PackageMeta(versionCode = pi.versionCode, lastUpdateTime = pi.lastUpdateTime)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (t: Throwable) {
        CLogUtils.w(TAG, "readPackageMetaFromPm($packageName) 异常: ${t.message}")
        null
    }

    /**
     * 在 buildClassLoader 路径上更新 [packageMeta]。spec § 4.2:**全 spec 唯一调 PM
     * 拿版本号的地方之一**。
     *
     * M4 修复 / defense-in-depth:若 packageMeta 中已存在旧值且与新读到的不一致,
     * 视为 APK 升级触发三件套清缓存(persistentCache.invalidate + signatureInvalidator
     * + 同步 hot stubMaps 清理)。即使 prepareAsync 入口已在 B1 修复中先做比对,
     * 这一层用作兜底 —— 任何重新调 buildClassLoader 的路径(如直接调用、未来重构)
     * 都会触发清缓存。
     */
    private fun recordPackageMeta(packageName: String) {
        val newMeta = readPackageMetaFromPm(packageName) ?: return
        val old = packageMeta[packageName]
        if (old != null && old != newMeta) {
            CLogUtils.i(
                TAG,
                "recordPackageMeta($packageName) APK 升级 ${old.versionCode}→${newMeta.versionCode} " +
                    "/ ${old.lastUpdateTime}→${newMeta.lastUpdateTime},清缓存三件套(defense-in-depth)",
            )
            persistentCache?.invalidate(packageName)
            signatureInvalidator?.invoke(packageName)
            stubMaps.keys.removeAll { it.startsWith("$packageName|") }
        }
        packageMeta[packageName] = newMeta
    }

    private suspend fun buildClassLoader(packageName: String) {
        buildMutex.withLock {
            // double-check after acquiring lock
            if (classLoaders.containsKey(packageName)) return
            // 预热入口已经判过 shouldRetry,这里只兜底:还在退避窗口就不要再尝试。
            // 注:此处不再依赖 PM 元数据(测试 path 可能没有 PM),仅按 isFailed + computeBackoffMs。

            val ai = try {
                context.packageManager.getApplicationInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                CLogUtils.w(TAG, "包名找不到: $packageName")
                recordFailure(packageName, ErrorKind.PERMANENT, ver = -1, mtime = -1L)
                return
            }

            // spec § 4.2:在 getApplicationInfo 紧邻处调 getPackageInfo 写 packageMeta。
            recordPackageMeta(packageName)

            val basePath = ai.sourceDir
            if (basePath.isNullOrBlank()) {
                CLogUtils.w(TAG, "包 $packageName 没有 sourceDir")
                val meta = packageMeta[packageName]
                recordFailure(
                    packageName,
                    ErrorKind.PERMANENT,
                    ver = meta?.versionCode ?: -1,
                    mtime = meta?.lastUpdateTime ?: -1L,
                )
                return
            }
            @Suppress("UNCHECKED_CAST")
            val splitPaths = ai.splitSourceDirs as Array<String?>?

            val dexDir = File(context.filesDir, DEX_DIR).apply { mkdirs() }
            // 用 packageName 直接命名,APK 升级时覆盖即可
            val dest = File(dexDir, "$packageName.apk")

            val appUid = context.applicationInfo.uid
            val cmd = "cp '$basePath' '${dest.absolutePath}' && " +
                    "chown $appUid:$appUid '${dest.absolutePath}' && " +
                    "chmod 644 '${dest.absolutePath}'"
            val result = rootManager.executeRootCommand(cmd)
            if (!result.isSuccess || !dest.exists() || dest.length() == 0L) {
                CLogUtils.e(
                    TAG,
                    "复制 APK 失败 ($packageName): isSuccess=${result.isSuccess}, " +
                            "exists=${dest.exists()}, size=${dest.length()}, " +
                            "stderr=${result.err.joinToString()}"
                )
                val meta = packageMeta[packageName]
                recordFailure(
                    packageName,
                    ErrorKind.TRANSIENT,
                    ver = meta?.versionCode ?: -1,
                    mtime = meta?.lastUpdateTime ?: -1L,
                )
                return
            }

            val combinedDexPath = ApkPathCollector.composeLoaderDexPath(dest.absolutePath, splitPaths)
            val cl = tryBuildClassLoader(combinedDexPath)
                ?: run {
                    if (combinedDexPath != dest.absolutePath) {
                        CLogUtils.w(
                            TAG,
                            "DexClassLoader($packageName) 含 splits 失败,fallback 到仅 base"
                        )
                        tryBuildClassLoader(dest.absolutePath)
                    } else null
                }

            if (cl == null) {
                CLogUtils.e(TAG, "构建 DexClassLoader 失败 ($packageName)")
                val meta = packageMeta[packageName]
                recordFailure(
                    packageName,
                    ErrorKind.TRANSIENT,
                    ver = meta?.versionCode ?: -1,
                    mtime = meta?.lastUpdateTime ?: -1L,
                )
                return
            }

            classLoaders[packageName] = cl
            // 成功后清掉 failedPackages 记录(若有),允许后续读入。
            synchronized(failedLock) { failedPackages.remove(packageName) }
            val splitCount = splitPaths?.count { !it.isNullOrBlank() } ?: 0
            CLogUtils.i(
                TAG,
                "为 $packageName 构建 ClassLoader 成功 (base=${dest.length() / 1024}KB, splits=$splitCount)"
            )
        }
    }

    private fun tryBuildClassLoader(dexPath: String): ClassLoader? = try {
        DexClassLoader(
            dexPath,
            null,                       // optimizedDirectory: Android 8+ 用默认
            null,                       // libraryPath: 不需要 native 库
            javaClass.classLoader       // parent: 本 App 的 classloader(framework 类走它)
        )
    } catch (t: Throwable) {
        CLogUtils.w(TAG, "DexClassLoader 构造失败 dexPath=$dexPath: ${t.message}")
        null
    }

    /**
     * 记一次失败到 [failedPackages]。已存在则递增 [FailedEntry.failCount];
     * 超过 LRU 上限时淘汰最老 entry。
     *
     * @param ver / [mtime] 调用点抓到的 PM 元数据快照。NameNotFound 路径填 -1 占位,
     *   保证 [shouldRetry] 在下次调用时若 PM 状态确实变化(例如包重新安装)能立刻复用 true。
     */
    internal fun recordFailure(
        packageName: String,
        kind: ErrorKind,
        ver: Int,
        mtime: Long,
        now: Long = System.currentTimeMillis(),
    ) {
        synchronized(failedLock) {
            val prev = failedPackages[packageName]
            val next = if (prev == null) {
                FailedEntry(
                    firstFailAt = now,
                    lastFailAt = now,
                    failCount = 1,
                    versionCode = ver,
                    lastUpdateTime = mtime,
                    errorKind = kind,
                )
            } else {
                FailedEntry(
                    firstFailAt = prev.firstFailAt,
                    lastFailAt = now,
                    failCount = prev.failCount + 1,
                    versionCode = ver,
                    lastUpdateTime = mtime,
                    // 升级后保留更严重一档(PERMANENT 优先于 TRANSIENT)
                    errorKind = if (prev.errorKind == ErrorKind.PERMANENT) ErrorKind.PERMANENT else kind,
                )
            }
            failedPackages[packageName] = next
            evictFailedIfOverflow()
        }
    }

    /**
     * 调用前必须已持有 [failedLock]。
     */
    private fun evictFailedIfOverflow() {
        while (failedPackages.size > FAILED_PACKAGES_MAX) {
            val it = failedPackages.entries.iterator()
            if (!it.hasNext()) break
            val oldest = it.next()
            it.remove()
            CLogUtils.i(
                TAG,
                "telemetry cache=failed_packages level=entry reason=lru_evict " +
                    "pkg=${oldest.key} iface=null " +
                    "entryCount=${failedPackages.size} bytes=null " +
                    "maxEntries=$FAILED_PACKAGES_MAX maxPackages=null " +
                    "maxBytesPerPackage=null totalSoftLimitBytes=null",
            )
        }
    }
}
