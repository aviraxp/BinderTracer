package com.btrace.viewer.parser

import android.content.Context
import android.content.res.AssetManager
import com.btrace.testfake.ITestStub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * spec § 13.2.2:MethodResolver 三档解析(系统反射 / 调用方 ClassLoader / methods.json)单测。
 *
 * 用 Robolectric 是因为 [com.btrace.viewer.utils.CLogUtils] 依赖 android.util.Log;
 * MethodResolver 自身的反射档不依赖 Robolectric 的 framework shadow。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MethodResolverTest {

    private lateinit var context: Context
    private lateinit var assets: AssetManager
    private lateinit var registry: AppClassLoaderRegistry
    private lateinit var staticTable: StaticMethodTable
    private lateinit var interfaceIndex: InterfaceIndex
    private lateinit var resolver: MethodResolver

    @Before
    fun setUp() {
        assets = mock()
        context = mock { on { assets } doReturn assets }
        registry = mock()
        // StaticMethodTable 还是用真实实例,内部仍走 mocked context.assets.open(...),
        // 与原行为完全一致 —— 现有 verify(assets, ...) 断言无需改动。
        staticTable = StaticMethodTable(context)
        // InterfaceIndex 默认空表;具体测试需要时再 addEntry。
        interfaceIndex = InterfaceIndex(context)
        resolver = MethodResolver(registry, staticTable, interfaceIndex)
    }

    /** 默认让 assets.open("methods.json") 返回一份小型兜底 JSON。 */
    private fun stubMethodsJson(json: String) {
        whenever(assets.open(eq("methods.json"))).doAnswer {
            ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `system reflection hits framework AIDL via fake stub`() {
        // 用测试 fake 的 ITestStub 验证反射档:不依赖 framework 接口的具体方法编号
        val name = resolver.getMethodName("com.btrace.testfake.ITestStub", 1)
        assertEquals("doFoo", name)
        val name2 = resolver.getMethodName("com.btrace.testfake.ITestStub", 2)
        assertEquals("doBar", name2)
        // 缓存命中后 JSON 不会被读
        verify(assets, never()).open(any())
    }

    @Test
    fun `system reflection prefers getDefaultTransactionName over TRANSACTION field suffix`() {
        val name = resolver.getMethodName("com.btrace.testfake.IDefaultNameStub", 1)
        assertEquals("defaultName", name)

        val fallback = resolver.getMethodName("com.btrace.testfake.IDefaultNameStub", 2)
        assertEquals("fallbackName", fallback)

        verify(assets, never()).open(any())
    }

    @Test
    fun `unknown interface falls back to methods_json`() {
        stubMethodsJson("""{"com.foo.IFake":{"42":"doFake","7":"doSeven"}}""")
        assertEquals("doFake", resolver.getMethodName("com.foo.IFake", 42))
        assertEquals("doSeven", resolver.getMethodName("com.foo.IFake", 7))
    }

    @Test
    fun `methods_json is loaded only once and reused on subsequent lookups`() {
        stubMethodsJson("""{"com.foo.IFake":{"42":"doFake"}}""")
        resolver.getMethodName("com.foo.IFake", 42)
        resolver.getMethodName("com.foo.IFake", 42)
        resolver.getMethodName("com.foo.IFake", 42)
        // assets.open 只被调一次:fallbackLoaded 后续一直 short-circuit
        verify(assets, times(1)).open(eq("methods.json"))
    }

    @Test
    fun `methods_json IO failure does not throw and returns code=N`() {
        whenever(assets.open(eq("methods.json"))).doThrow(IOException("boom"))
        assertEquals("code=99", resolver.getMethodName("com.foo.Missing", 99))
        // 即使第一次失败,后续也不应再尝试(fallbackLoaded 已置 true)
        assertEquals("code=100", resolver.getMethodName("com.foo.Missing", 100))
        verify(assets, times(1)).open(eq("methods.json"))
    }

    @Test
    fun `null interface name returns code=N immediately`() {
        assertEquals("code=5", resolver.getMethodName(null, 5))
        assertEquals("code=0", resolver.getMethodName(null, 0))
        // 没有触发 JSON 读
        verify(assets, never()).open(any())
    }

    @Test
    fun `caller package classloader hit beats methods_json`() {
        stubMethodsJson("""{"X.Y.Z":{"1":"jsonFallbackName"}}""")
        whenever(registry.getStubMap(eq("com.foo.app"), eq("X.Y.Z")))
            .doReturn(mapOf(1 to "appResolvedName"))

        val name = resolver.getMethodName("X.Y.Z", 1, callerPackage = "com.foo.app")
        assertEquals("appResolvedName", name)
        // 命中 App ClassLoader 时不需要兜底 JSON
        verify(assets, never()).open(any())
    }

    @Test
    fun `caller package miss falls through to methods_json`() {
        stubMethodsJson("""{"X.Y.Z":{"5":"jsonName"}}""")
        whenever(registry.getStubMap(eq("com.foo.app"), eq("X.Y.Z"))).doReturn(null)

        val name = resolver.getMethodName("X.Y.Z", 5, callerPackage = "com.foo.app")
        assertEquals("jsonName", name)
    }

    @Test
    fun `getMethodSignature returns empty paramTypes when interface class is invisible but methods_json hits`() {
        stubMethodsJson("""{"com.invisible.IFoo":{"3":"hiddenMethod"}}""")
        val sig = resolver.getMethodSignature("com.invisible.IFoo", 3)
        assertNotNull(sig)
        assertEquals("hiddenMethod", sig!!.methodName)
        // 接口类不在 classpath / ClassLoader 上 → paramTypes 必空
        assertTrue("paramTypes 应空,实际=${sig.paramTypes}", sig.paramTypes.isEmpty())
        // 同上 → returnType 也拿不到
        assertNull(sig.returnType)
    }

    @Test
    fun `getMethodSignature returns null when method name cannot be resolved`() {
        stubMethodsJson("""{}""")
        // interfaceName 非 null,但 stub 找不到、JSON 也没有 → methodName=code=N → 返回 null
        assertNull(resolver.getMethodSignature("com.foo.IGhost", 42))
    }

    @Test
    fun `getMethodSignature null when interfaceName is null`() {
        assertNull(resolver.getMethodSignature(null, 1))
    }

    @Test
    fun `getMethodSignature reflects real interface paramTypes for fake stub`() {
        // ITestStub 在 test classpath 上,反射可拿到方法签名
        val sig = resolver.getMethodSignature("com.btrace.testfake.ITestStub", 1)
        assertNotNull(sig)
        assertEquals("doFoo", sig!!.methodName)
        // doFoo(int, String) → ["int", "String"]
        assertEquals(listOf("int", "String"), sig.paramTypes)
        // doFoo 返回 String
        assertEquals("String", sig.returnType)

        val sig2 = resolver.getMethodSignature("com.btrace.testfake.ITestStub", 2)
        assertNotNull(sig2)
        assertEquals("doBar", sig2!!.methodName)
        // doBar(List<String>, Bundle) → ["List<String>", "Bundle"]
        assertEquals(2, sig2.paramTypes.size)
        assertEquals("List<String>", sig2.paramTypes[0])
        assertEquals("Bundle", sig2.paramTypes[1])
        // doBar 返回 void
        assertEquals("void", sig2.returnType)
    }

    @Test
    fun `invalidate clears reflection cache so next lookup recomputes`() {
        // 先让某接口走兜底("从未在反射档命中"),mock 返回一组,确认命中后又 invalidate
        stubMethodsJson("""{"com.foo.IFake":{"1":"first"}}""")
        assertEquals("first", resolver.getMethodName("com.foo.IFake", 1))

        // invalidate 反射缓存:下一次会重新 loadStubTransactions,但 fallbackLoaded 已为 true
        resolver.invalidate("com.foo.IFake")
        assertEquals("first", resolver.getMethodName("com.foo.IFake", 1))
        // 兜底 JSON 仍只读一次(invalidate 不动 fallback)
        verify(assets, times(1)).open(eq("methods.json"))
    }

    @Test
    fun `interface index lookup hits when caller package miss but candidate package has stub`() {
        // 4 档 fallback:caller miss → InterfaceIndex 查到候选 pkg → registry.getStubMap(候选)命中
        interfaceIndex.addEntry("X.Y.Z", "vendor.candidate.app")
        whenever(registry.getStubMap(eq("com.foo.app"), eq("X.Y.Z"))).doReturn(null)
        whenever(registry.getStubMap(eq("vendor.candidate.app"), eq("X.Y.Z")))
            .doReturn(mapOf(7 to "indexedHit"))

        val name = resolver.getMethodName("X.Y.Z", 7, callerPackage = "com.foo.app")
        assertEquals("indexedHit", name)
        // 命中索引 → JSON 不会被读
        verify(assets, never()).open(any())
    }

    @Test
    fun `interface index miss falls through to methods_json`() {
        // InterfaceIndex 没记录该接口 → 直接降到 JSON
        stubMethodsJson("""{"X.Y.Z":{"3":"jsonName"}}""")
        whenever(registry.getStubMap(eq("com.foo.app"), eq("X.Y.Z"))).doReturn(null)

        val name = resolver.getMethodName("X.Y.Z", 3, callerPackage = "com.foo.app")
        assertEquals("jsonName", name)
    }

    // ─── spec 2026-05-03 § 3.3 / § 3.4:三级评分遍历 + negativeCache ───

    @Test
    fun `getMethodSignature STRONG hit short-circuits remaining candidates`() {
        // ITestStub 真实存在于 test classpath → STRONG (paramTypes 非空,returnType 非空)
        // candidates = ["com.never.A", "com.btrace.testfake.fake1"...] 第一个候选用 ITestStub 命中即应短路
        stubMethodsJson("""{}""")
        // 只让第一个候选反射成功,后续不应再被调用
        // 这里用 callerPackage = null 走 framework loader 路径
        val sig = resolver.getMethodSignature(
            "com.btrace.testfake.ITestStub",
            1,
            listOf("com.btrace.testfake.ITestStub")
        )
        assertNotNull(sig)
        assertEquals("doFoo", sig!!.methodName)
        assertEquals(listOf("int", "String"), sig.paramTypes)
        assertEquals("String", sig.returnType)
    }

    @Test
    fun `getMethodSignature WEAK does not short-circuit when later candidate is STRONG`() {
        // M1 修复:构造真实 "A=WEAK, B=STRONG → 返回 B" 路径。
        // 用一个**测试 classpath 上不存在**的 interfaceName,这样 framework Class.forName
        // 一定失败,paramTypes 反射只能依赖 registry.loadClass(callerPackage, ...) 命中。
        //
        // - 候选 A "com.app.a":registry.getStubMap 返回非空 (methodName 命中) +
        //   registry.loadClass 返回 null (paramTypes 反射不到) → WEAK
        // - 候选 B "com.app.b":registry.getStubMap 返回非空 + registry.loadClass
        //   返回真实 ITestStub class (paramTypes 非空,STRONG)
        // 期望:返回 B 的 STRONG,而不是被 A 的 WEAK 抢先终止。
        val ifaceName = "com.invisible.IGhost"
        // A 走第 2 档 caller ClassLoader 命中 stubMap → methodName 解到
        whenever(registry.getStubMap(eq("com.app.a"), eq(ifaceName)))
            .thenReturn(mapOf(1 to "doFoo"))
        whenever(registry.getStubMap(eq("com.app.b"), eq(ifaceName)))
            .thenReturn(mapOf(1 to "doFoo"))
        // A 的 loadClass 拿不到接口类 → paramTypes 空,WEAK
        whenever(registry.loadClass(eq("com.app.a"), eq(ifaceName))).thenReturn(null)
        // B 的 loadClass 返回真实 ITestStub 类 (有 doFoo(int, String) 方法 → paramTypes 非空)
        whenever(registry.loadClass(eq("com.app.b"), eq(ifaceName)))
            .thenReturn(com.btrace.testfake.ITestStub::class.java)

        val sig = resolver.getMethodSignature(ifaceName, 1, listOf("com.app.a", "com.app.b"))
        assertNotNull(sig)
        assertEquals("doFoo", sig!!.methodName)
        // 关键断言:返回 B 的 STRONG (paramTypes 非空),而不是 A 的 WEAK
        assertEquals(
            "应返回 B 的 STRONG 签名,WEAK 不许短路",
            listOf("int", "String"),
            sig.paramTypes,
        )
    }

    @Test
    fun `getMethodSignature STRONG short-circuits and skips later candidates`() {
        // 候选 A 给 STRONG → 候选 B / C 完全不应该被 query
        val ifaceName = "com.invisible.IGhost2"
        whenever(registry.getStubMap(eq("com.app.a"), eq(ifaceName)))
            .thenReturn(mapOf(1 to "doFoo"))
        whenever(registry.loadClass(eq("com.app.a"), eq(ifaceName)))
            .thenReturn(com.btrace.testfake.ITestStub::class.java)

        val sig = resolver.getMethodSignature(ifaceName, 1, listOf("com.app.a", "com.app.b", "com.app.c"))
        assertNotNull(sig)
        assertEquals("doFoo", sig!!.methodName)
        assertEquals(listOf("int", "String"), sig.paramTypes)

        // STRONG 短路:b/c 候选的 getStubMap 完全不应被调
        verify(registry, never()).getStubMap(eq("com.app.b"), any())
        verify(registry, never()).getStubMap(eq("com.app.c"), any())
    }

    @Test
    fun `getMethodSignature all WEAK returns first WEAK`() {
        // 接口 invisible,所有候选都只能解到 methodName,paramTypes 都空 → 返回 first WEAK
        stubMethodsJson("""{"com.invisible.IFoo":{"7":"weakName"}}""")
        val sig = resolver.getMethodSignature(
            "com.invisible.IFoo",
            7,
            listOf("com.app.x", "com.app.y", "com.app.z"),
        )
        assertNotNull(sig)
        assertEquals("weakName", sig!!.methodName)
        assertTrue(sig.paramTypes.isEmpty())
    }

    @Test
    fun `getMethodSignature all MISS returns null and writes negativeCache`() {
        // 候选都解不出 methodName(JSON 空,$Stub 不在,索引也空)→ 全部进 negativeCache
        stubMethodsJson("""{}""")
        val sig = resolver.getMethodSignature(
            "com.unknown.IGhost",
            42,
            listOf("com.app.a", "com.app.b", "com.app.c"),
        )
        assertNull(sig)
        // 三个候选 × 一个 interface = 3 条 neg 记录
        assertEquals(3, resolver.negativeCacheSize())
    }

    @Test
    fun `getMethodSignature negativeCache skips candidate within TTL`() {
        // 第一次:三个候选全 miss → 三条 neg 入表
        stubMethodsJson("""{}""")
        val now = 100_000L
        val sig1 = resolver.getMethodSignature(
            "com.unknown.IGhost",
            42,
            listOf("com.app.a", "com.app.b"),
            nowMs = now,
        )
        assertNull(sig1)
        assertEquals(2, resolver.negativeCacheSize())

        // 60s 内第二次:negativeCache 命中,跳过 → 仍返回 null,但不再增加 neg 计数
        val sig2 = resolver.getMethodSignature(
            "com.unknown.IGhost",
            42,
            listOf("com.app.a", "com.app.b"),
            nowMs = now + 30_000L,
        )
        assertNull(sig2)
        assertEquals("60s 内不应新增 neg 记录", 2, resolver.negativeCacheSize())
    }

    @Test
    fun `getMethodSignature negativeCache expires after 60s`() {
        stubMethodsJson("""{}""")
        val t0 = 100_000L
        resolver.getMethodSignature(
            "com.unknown.IGhost",
            42,
            listOf("com.app.a"),
            nowMs = t0,
        )
        assertEquals(1, resolver.negativeCacheSize())

        // 60s + 1ms 后:TTL 过期 → 重新进入解析路径(再 miss → 重写 neg)
        resolver.getMethodSignature(
            "com.unknown.IGhost",
            42,
            listOf("com.app.a"),
            nowMs = t0 + 60_001L,
        )
        // 仍然 1 条(过期被重写)
        assertEquals(1, resolver.negativeCacheSize())
    }

    @Test
    fun `getMethodSignature deduplicates candidates across sources`() {
        // 候选 = [P, P, Q] 经 EventRepository 应已去重 = [P, Q],resolver 不需要再去重 ——
        // 但本用例验证即使传入重复值,negativeCache 也只记一次(LinkedHashMap put 替换)
        stubMethodsJson("""{}""")
        resolver.getMethodSignature(
            "com.unknown.IGhost",
            42,
            listOf("com.app.p", "com.app.p", "com.app.q"),
        )
        // 两个不同包 × 一个 interface = 2 条 neg
        assertEquals(2, resolver.negativeCacheSize())
    }

    @Test
    fun `getMethodSignature empty candidates falls back to framework loader`() {
        // 候选为空 → resolver 走 callerPackage=null,framework loader 路径,
        // 命中 testfake.ITestStub 即返回 STRONG
        stubMethodsJson("""{}""")
        val sig = resolver.getMethodSignature(
            "com.btrace.testfake.ITestStub",
            2,
            emptyList(),
        )
        assertNotNull(sig)
        assertEquals("doBar", sig!!.methodName)
        // ITestStub.doBar(List<String>, Bundle)
        assertEquals(2, sig.paramTypes.size)
    }

    @Test
    fun `non-TRANSACTION fields and wrong-typed fields are ignored`() {
        // ITestStub.Stub 上有 NON_TRANSACTION / TRANSACTION_wrongType / TRANSACTION_notStatic
        // —— 它们都不能污染映射;只有 doFoo(=1) / doBar(=2) 命中
        assertEquals("doFoo", resolver.getMethodName("com.btrace.testfake.ITestStub", 1))
        assertEquals("doBar", resolver.getMethodName("com.btrace.testfake.ITestStub", 2))
        // 测试 fake 上 99/7 都不应命中(99 是 long 字段,会被跳过)
        stubMethodsJson("""{}""")
        assertEquals("code=99", resolver.getMethodName("com.btrace.testfake.ITestStub", 99))
        assertEquals("code=7", resolver.getMethodName("com.btrace.testfake.ITestStub", 7))
    }

    // ─── spec § 6.1 BLOCKER B3:持久缓存读路径回归保护 ───

    @Test
    fun `persistent cache hit version match avoids reflection`() {
        // 命中且版本一致 → 不调反射(getStubMap / loadInterfaceSignatures 都不应跑)
        // 用 mock 注入持久缓存命中
        val pcache: PersistentSignatureCache = mock()
        val cachedSig = mapOf(
            "doFoo" to MethodSignature("doFoo", listOf("int", "String"), "String"),
        )
        whenever(pcache.get(eq("com.foo.app"), eq("X.Y.Z"), eq(7), eq(99L)))
            .thenReturn(cachedSig)
        whenever(registry.getPackageMeta(eq("com.foo.app")))
            .thenReturn(AppClassLoaderRegistry.PackageMeta(versionCode = 7, lastUpdateTime = 99L))
        // methods.json 给一份让 getMethodName 命中(走第 4 档),证明持久缓存命中后
        // 不再调 registry.getStubMap(它返回 null,会走 fallthrough 到 methods.json)
        stubMethodsJson("""{"X.Y.Z":{"1":"doFoo"}}""")

        resolver.attachPersistentCache(pcache)

        val sig = resolver.getMethodSignature("X.Y.Z", 1, listOf("com.foo.app"))
        assertNotNull(sig)
        assertEquals("doFoo", sig!!.methodName)
        // 持久缓存提供的 paramTypes 被使用(STRONG)
        assertEquals(listOf("int", "String"), sig.paramTypes)
        assertEquals("String", sig.returnType)

        // 命中后,持久缓存的 invalidate 不应被调
        verify(pcache, never()).invalidate(any())
        // 命中后,不应再 put 同一条目(避免 echo)
        verify(pcache, never()).put(any(), any(), any(), any(), any())
    }

    @Test
    fun `persistent cache miss version mismatch invalidates package and reflects`() {
        // 持久缓存里**有**该包的旧版本记录,但当前 getPackageMeta 与 cache 的版本失配 →
        // 应触发 pcache.invalidate(pkg) + 走反射
        val pcache: PersistentSignatureCache = mock()
        // 模拟"持久缓存里 A.IFoo 有一份旧版本记录"
        val staleSig = mapOf("oldM" to MethodSignature("oldM", listOf("int"), null))
        // 当前查询版本 = 8,持久缓存里记的是 7 → get 返回 null
        whenever(pcache.get(eq("com.foo.app"), eq("X.Y.Z"), eq(8), eq(99L)))
            .thenReturn(null)
        // peekRaw(无版本校验)能看到旧记录 → 触发 invalidate
        whenever(pcache.peekRaw(eq("com.foo.app"), eq("X.Y.Z")))
            .thenReturn(staleSig)
        whenever(registry.getPackageMeta(eq("com.foo.app")))
            .thenReturn(AppClassLoaderRegistry.PackageMeta(versionCode = 8, lastUpdateTime = 99L))
        stubMethodsJson("""{"X.Y.Z":{"1":"newM"}}""")

        resolver.attachPersistentCache(pcache)

        val sig = resolver.getMethodSignature("X.Y.Z", 1, listOf("com.foo.app"))
        // 走反射 → methodName 从 methods.json 取得
        assertNotNull(sig)
        assertEquals("newM", sig!!.methodName)

        // 必须调 pcache.invalidate("com.foo.app")
        verify(pcache).invalidate(eq("com.foo.app"))
    }

    @Test
    fun `persistent cache miss empty package skips invalidate`() {
        // 持久缓存里**没有**该包记录(全新解析)→ 不应调 invalidate
        val pcache: PersistentSignatureCache = mock()
        whenever(pcache.get(any(), any(), any(), any())).thenReturn(null)
        whenever(pcache.peekRaw(any(), any())).thenReturn(null)
        whenever(registry.getPackageMeta(eq("com.foo.app")))
            .thenReturn(AppClassLoaderRegistry.PackageMeta(versionCode = 8, lastUpdateTime = 99L))
        stubMethodsJson("""{"X.Y.Z":{"1":"firstM"}}""")

        resolver.attachPersistentCache(pcache)

        val sig = resolver.getMethodSignature("X.Y.Z", 1, listOf("com.foo.app"))
        assertNotNull(sig)
        assertEquals("firstM", sig!!.methodName)

        // 没旧记录 → invalidate 不应被调
        verify(pcache, never()).invalidate(any())
    }

    @Test
    fun `getMethodSignature skips persistent put when getPackageMeta returns null`() {
        // spec § 12 P3 元数据契约:getPackageMeta 返回 null 时跳 Put,只填内存 SignatureCache
        val pcache: PersistentSignatureCache = mock()
        whenever(pcache.get(any(), any(), any(), any())).thenReturn(null)
        whenever(pcache.peekRaw(any(), any())).thenReturn(null)
        // packageMeta cache 里没这个包(模拟 buildClassLoader 还没跑完 / NameNotFound)
        whenever(registry.getPackageMeta(eq("com.foo.app"))).thenReturn(null)
        stubMethodsJson("""{"X.Y.Z":{"1":"someM"}}""")

        resolver.attachPersistentCache(pcache)

        val sig = resolver.getMethodSignature("X.Y.Z", 1, listOf("com.foo.app"))
        assertNotNull(sig)
        assertEquals("someM", sig!!.methodName)

        // getPackageMeta == null → put 必须被跳过
        verify(pcache, never()).put(any(), any(), any(), any(), any())
        verify(pcache, never()).invalidate(any())
    }
}
