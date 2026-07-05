package com.btrace.viewer.parser

import android.content.Context
import com.btrace.viewer.root.RootManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec 2026-05-03 § 4.5 退避表 + 三件套清缓存 + LRU 单测。
 *
 * 大部分单测用纯逻辑路径覆盖(recordFailure / shouldRetry / computeBackoffMs);
 * APK 升级三件套清缓存路径用 [PersistentSignatureCache] / SignatureInvalidator 桩
 * 来观测 invalidate 调用是否触发。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppClassLoaderRegistryTest {

    private lateinit var context: Context
    private lateinit var rootManager: RootManager
    private lateinit var registry: AppClassLoaderRegistry

    @Before
    fun setUp() {
        // Robolectric 提供真实 Context;PackageManager 在测试里 NameNotFound,
        // 但 recordFailure / shouldRetry / packageMeta cache 路径都不依赖 PM。
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        rootManager = mock()
        registry = AppClassLoaderRegistry(context, rootManager)
    }

    // ─── 退避表 8 个 failCount(TRANSIENT)─────

    @Test
    fun `backoff transient failCount 1 to 8 follows exponential schedule with 1h cap`() {
        val cases = listOf(
            1 to 30_000L,
            2 to 60_000L,
            3 to 120_000L,
            4 to 240_000L,
            5 to 480_000L,
            6 to 960_000L,
            7 to 1_920_000L,
            8 to 3_600_000L,    // cap
            9 to 3_600_000L,    // 仍 cap
            20 to 3_600_000L,   // 仍 cap
        )
        for ((failCount, expected) in cases) {
            val actual = AppClassLoaderRegistry.computeBackoffMs(failCount, AppClassLoaderRegistry.ErrorKind.TRANSIENT)
            assertEquals("failCount=$failCount transient", expected, actual)
        }
    }

    // ─── PERMANENT 24h 封顶,TRANSIENT 1h 封顶 ─────

    @Test
    fun `backoff permanent uses 24h cap, transient uses 1h cap`() {
        // spec § 4.5 公式 = min(30_000 * 2^(failCount-1 ∈ [0,7]), cap)。
        // failCount=8 时 30_000 * 2^7 = 3_840_000 ms。
        //   - TRANSIENT cap=1h=3_600_000 → backoff 被压到 3_600_000
        //   - PERMANENT cap=24h=86_400_000 → backoff 取真实指数值 3_840_000
        assertEquals(
            "transient failCount=8 应被压到 1h cap",
            3_600_000L,
            AppClassLoaderRegistry.computeBackoffMs(8, AppClassLoaderRegistry.ErrorKind.TRANSIENT),
        )
        assertEquals(
            "permanent failCount=8 不到 24h cap,取指数值 3_840_000",
            3_840_000L,
            AppClassLoaderRegistry.computeBackoffMs(8, AppClassLoaderRegistry.ErrorKind.PERMANENT),
        )
        // shift 封顶 7,所以 backoff ≤ 3_840_000;PERMANENT 路径下永远小于 24h cap。
        assertEquals(
            3_840_000L,
            AppClassLoaderRegistry.computeBackoffMs(20, AppClassLoaderRegistry.ErrorKind.PERMANENT),
        )
        // 早期(< 8):退避值同 transient(因为 30_000 << 24h),还没触到任何 cap
        assertEquals(30_000L, AppClassLoaderRegistry.computeBackoffMs(1, AppClassLoaderRegistry.ErrorKind.PERMANENT))
    }

    // ─── shouldRetry:窗口前不重试,过窗口后重试 ─────

    @Test
    fun `shouldRetry returns false within window, true after`() {
        val pkg = "com.example.flaky"
        val t0 = 1_000_000L
        registry.recordFailure(pkg, AppClassLoaderRegistry.ErrorKind.TRANSIENT, ver = 1, mtime = 100L, now = t0)

        // 30s 内 false
        assertFalse(registry.shouldRetry(pkg, 1, 100L, now = t0 + 29_999L))
        // 30s 整 true
        assertTrue(registry.shouldRetry(pkg, 1, 100L, now = t0 + 30_000L))
    }

    @Test
    fun `shouldRetry failCount 8 transient hits 1h cap`() {
        val pkg = "com.example.repeat"
        val t0 = 1_000_000L
        // 连续记录 8 次失败
        for (i in 0 until 8) {
            registry.recordFailure(pkg, AppClassLoaderRegistry.ErrorKind.TRANSIENT, ver = 1, mtime = 100L, now = t0 + i * 1L)
        }
        // 1h - 1ms 内 false
        val last = registry.peekFailed(pkg)!!.lastFailAt
        assertFalse(registry.shouldRetry(pkg, 1, 100L, now = last + 3_599_999L))
        // 1h 整 true
        assertTrue(registry.shouldRetry(pkg, 1, 100L, now = last + 3_600_000L))
    }

    @Test
    fun `shouldRetry failCount 8 permanent hits 24h cap`() {
        val pkg = "com.example.gone"
        val t0 = 1_000_000L
        for (i in 0 until 8) {
            registry.recordFailure(pkg, AppClassLoaderRegistry.ErrorKind.PERMANENT, ver = -1, mtime = -1L, now = t0 + i * 1L)
        }
        val last = registry.peekFailed(pkg)!!.lastFailAt
        assertFalse(registry.shouldRetry(pkg, -1, -1L, now = last + 3_600_000L))
        assertTrue(registry.shouldRetry(pkg, -1, -1L, now = last + 86_400_000L))
    }

    // ─── APK 升级立即重置 ─────

    @Test
    fun `APK upgrade versionCode change resets entry immediately`() {
        val pkg = "com.example.upgrade"
        val t0 = 1_000_000L
        registry.recordFailure(pkg, AppClassLoaderRegistry.ErrorKind.TRANSIENT, ver = 100, mtime = 1L, now = t0)
        assertTrue(registry.isFailed(pkg))

        // versionCode 变了 → 立即返回 true 且清旧 entry
        assertTrue(registry.shouldRetry(pkg, currentVer = 101, currentMtime = 1L, now = t0 + 1L))
        assertFalse("entry 应已被清", registry.isFailed(pkg))
    }

    @Test
    fun `APK upgrade lastUpdateTime change resets entry immediately`() {
        val pkg = "com.example.mtime"
        val t0 = 1_000_000L
        registry.recordFailure(pkg, AppClassLoaderRegistry.ErrorKind.TRANSIENT, ver = 1, mtime = 100L, now = t0)
        assertTrue(registry.isFailed(pkg))

        // lastUpdateTime 变化 → 重置
        assertTrue(registry.shouldRetry(pkg, currentVer = 1, currentMtime = 200L, now = t0 + 1L))
        assertFalse(registry.isFailed(pkg))
    }

    // ─── failedPackages LRU 256 ─────

    @Test
    fun `failedPackages caps at 256 entries with LRU eviction`() {
        val cap = AppClassLoaderRegistry.FAILED_PACKAGES_MAX
        val t0 = 1_000_000L
        // 写入 cap + 5 条
        for (i in 0 until cap + 5) {
            registry.recordFailure(
                "com.pkg.$i",
                AppClassLoaderRegistry.ErrorKind.TRANSIENT,
                ver = 1, mtime = 1L,
                now = t0 + i.toLong(),
            )
        }
        assertEquals(cap, registry.failedSize())
        // 最早 5 条应被 evict(com.pkg.0..4)
        for (i in 0 until 5) {
            assertFalse("com.pkg.$i 应已被 evict", registry.isFailed("com.pkg.$i"))
        }
        // 最后一条仍在
        assertTrue(registry.isFailed("com.pkg.${cap + 4}"))
    }

    // ─── ErrorKind 升级:TRANSIENT 之后 PERMANENT 应保留 PERMANENT ─────

    @Test
    fun `ErrorKind upgrades to PERMANENT and stays`() {
        val pkg = "com.kind.upgrade"
        val t0 = 1L
        registry.recordFailure(pkg, AppClassLoaderRegistry.ErrorKind.TRANSIENT, 1, 1L, now = t0)
        registry.recordFailure(pkg, AppClassLoaderRegistry.ErrorKind.PERMANENT, 1, 1L, now = t0 + 1)
        // 即使下一次再标 TRANSIENT,errorKind 也保持 PERMANENT
        registry.recordFailure(pkg, AppClassLoaderRegistry.ErrorKind.TRANSIENT, 1, 1L, now = t0 + 2)
        assertEquals(
            AppClassLoaderRegistry.ErrorKind.PERMANENT,
            registry.peekFailed(pkg)!!.errorKind,
        )
    }

    // ─── packageMeta cache: getPackageMeta 在未 build 时 null ─────

    @Test
    fun `getPackageMeta returns null when not yet recorded`() {
        // 没跑过 buildClassLoader,packageMeta map 应为空
        assertNull(registry.getPackageMeta("com.never.touched"))
    }

    @Test
    fun `getStubMap prefers getDefaultTransactionName over TRANSACTION field suffix`() {
        registry.forceSetClassLoaderForTest("com.fake.app", javaClass.classLoader!!)

        val stubMap = registry.getStubMap(
            "com.fake.app",
            "com.btrace.testfake.IDefaultNameStub",
        )

        assertEquals("defaultName", stubMap?.get(1))
        assertEquals("fallbackName", stubMap?.get(2))
    }

    // ─── attachCacheInvalidators:APK 升级触发三件套(B2 真实断言)─────

    @Test
    fun `attachCacheInvalidators tolerates null without throwing`() {
        // 降级路径:两个 invalidator 都未注入时 prepareAsync 仍能跑(NameNotFound 走 PERMANENT 分支)。
        registry.attachCacheInvalidators(persistentCache = null, signatureInvalidator = null)
        registry.prepareAsync("com.no.such.pkg")  // 不抛异常即通过
    }

    @Test
    fun `prepareAsync APK upgrade triggers three-way invalidation`() {
        // 1) 用 spy + doReturn override readPackageMetaFromPm,模拟 PM 拿到 v2 元数据
        val spied = spy(AppClassLoaderRegistry(context, rootManager))
        val newMeta = AppClassLoaderRegistry.PackageMeta(versionCode = 200, lastUpdateTime = 2_000L)
        doReturn(newMeta).whenever(spied).readPackageMetaFromPm(eq("com.upgrade.app"))

        // 2) 模拟"上次 build 成功"的状态:packageMeta + classLoaders 都已就绪,版本 v1
        val oldMeta = AppClassLoaderRegistry.PackageMeta(versionCode = 100, lastUpdateTime = 1_000L)
        spied.forceSetPackageMeta("com.upgrade.app", oldMeta)
        spied.forceSetClassLoaderForTest("com.upgrade.app", javaClass.classLoader!!)
        assertTrue(spied.isClassLoaderReadyForTest("com.upgrade.app"))

        // 3) 注入两个 invalidator
        val pcache: PersistentSignatureCache = mock()
        var sigInvalidatedCount = 0
        var sigInvalidatedPkg: String? = null
        spied.attachCacheInvalidators(
            persistentCache = pcache,
            signatureInvalidator = { pkg ->
                sigInvalidatedCount++
                sigInvalidatedPkg = pkg
            },
        )

        // 4) 调 prepareAsync → 检测到版本不一致(v1 → v2),应:
        //    a) 调 persistentCache.invalidate("com.upgrade.app")
        //    b) 调 signatureInvalidator("com.upgrade.app")
        //    c) 把 classLoaders 中该 pkg remove 掉(strict-precondition 强制重 build)
        //    d) packageMeta cache 升级到新值
        spied.prepareAsync("com.upgrade.app")

        verify(pcache).invalidate(eq("com.upgrade.app"))
        assertEquals(1, sigInvalidatedCount)
        assertEquals("com.upgrade.app", sigInvalidatedPkg)
        assertFalse(
            "APK 升级后旧 ClassLoader 应被清掉,等待重 build",
            spied.isClassLoaderReadyForTest("com.upgrade.app"),
        )
        assertEquals(newMeta, spied.getPackageMeta("com.upgrade.app"))
    }

    @Test
    fun `recordPackageMeta defense-in-depth invalidates on version mismatch`() {
        // M4 修复:即使绕过 prepareAsync 入口比对,buildClassLoader 内 recordPackageMeta
        // 这一层也应在 packageMeta 老值与新读到的不一致时触发三件套清缓存。
        val spied = spy(AppClassLoaderRegistry(context, rootManager))
        val newMeta = AppClassLoaderRegistry.PackageMeta(versionCode = 200, lastUpdateTime = 2_000L)
        doReturn(newMeta).whenever(spied).readPackageMetaFromPm(eq("com.depth.app"))

        // 灌入旧值,模拟"上次 build 完时记的元数据"
        val oldMeta = AppClassLoaderRegistry.PackageMeta(versionCode = 100, lastUpdateTime = 1_000L)
        spied.forceSetPackageMeta("com.depth.app", oldMeta)

        val pcache: PersistentSignatureCache = mock()
        var sigInvalidated = false
        spied.attachCacheInvalidators(
            persistentCache = pcache,
            signatureInvalidator = { sigInvalidated = true },
        )

        // 直接调 recordPackageMeta(模拟 buildClassLoader 内部路径)
        spied.recordPackageMetaForTest("com.depth.app")

        verify(pcache).invalidate(eq("com.depth.app"))
        assertTrue("recordPackageMeta 应在版本失配时调 signatureInvalidator", sigInvalidated)
        assertEquals(newMeta, spied.getPackageMeta("com.depth.app"))
    }

    @Test
    fun `recordPackageMeta no version change keeps caches intact`() {
        // 老值 == 新值 → 不应触发任何 invalidate(避免 echo)
        val spied = spy(AppClassLoaderRegistry(context, rootManager))
        val meta = AppClassLoaderRegistry.PackageMeta(versionCode = 100, lastUpdateTime = 1_000L)
        doReturn(meta).whenever(spied).readPackageMetaFromPm(eq("com.same2.app"))

        spied.forceSetPackageMeta("com.same2.app", meta)

        val pcache: PersistentSignatureCache = mock()
        var sigInvalidated = false
        spied.attachCacheInvalidators(
            persistentCache = pcache,
            signatureInvalidator = { sigInvalidated = true },
        )

        spied.recordPackageMetaForTest("com.same2.app")

        verify(pcache, never()).invalidate(any())
        assertFalse(sigInvalidated)
    }

    @Test
    fun `prepareAsync no upgrade keeps caches intact`() {
        // 包未变化 → 不应触发任何 invalidate 回调,不应清 ClassLoader。
        val spied = spy(AppClassLoaderRegistry(context, rootManager))
        val meta = AppClassLoaderRegistry.PackageMeta(versionCode = 100, lastUpdateTime = 1_000L)
        doReturn(meta).whenever(spied).readPackageMetaFromPm(eq("com.same.app"))

        spied.forceSetPackageMeta("com.same.app", meta)
        spied.forceSetClassLoaderForTest("com.same.app", javaClass.classLoader!!)

        val pcache: PersistentSignatureCache = mock()
        var sigInvalidated = false
        spied.attachCacheInvalidators(
            persistentCache = pcache,
            signatureInvalidator = { sigInvalidated = true },
        )

        spied.prepareAsync("com.same.app")

        verify(pcache, never()).invalidate(any())
        assertFalse("无升级时 signatureInvalidator 不应被调", sigInvalidated)
        assertTrue(spied.isClassLoaderReadyForTest("com.same.app"))
    }

    // ─── recordFailure 同包多次累计 failCount ─────

    @Test
    fun `recordFailure increments failCount and preserves firstFailAt`() {
        val pkg = "com.count"
        val t0 = 100L
        registry.recordFailure(pkg, AppClassLoaderRegistry.ErrorKind.TRANSIENT, 1, 1L, now = t0)
        registry.recordFailure(pkg, AppClassLoaderRegistry.ErrorKind.TRANSIENT, 1, 1L, now = t0 + 50)
        registry.recordFailure(pkg, AppClassLoaderRegistry.ErrorKind.TRANSIENT, 1, 1L, now = t0 + 100)
        val e = registry.peekFailed(pkg)!!
        assertEquals(3, e.failCount)
        assertEquals(t0, e.firstFailAt)
        assertEquals(t0 + 100, e.lastFailAt)
        assertNotEquals(e.firstFailAt, e.lastFailAt)
    }
}
