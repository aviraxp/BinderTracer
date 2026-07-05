package com.btrace.viewer

import android.app.Application
import android.os.Build
import com.btrace.viewer.utils.CLogUtils
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.HiltAndroidApp
import org.lsposed.hiddenapibypass.HiddenApiBypass

@HiltAndroidApp
class BTraceApp : Application() {

    companion object {
        private const val TAG = "BTraceApp"

        init {
            // 配置 libsu：设置为使用 root shell 和全局挂载命名空间
            Shell.enableVerboseLogging = true
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(10)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        CLogUtils.i(TAG, "onCreate() BTraceApp 启动")

        // Android 9+ 对 non-SDK 接口反射有限制;MethodResolver 需要读 android.*$Stub
        // 里的 TRANSACTION_* 常量,所以全局放开所有 hidden API 豁免。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
                CLogUtils.i(TAG, "HiddenApiBypass 已开启,所有 non-SDK 接口放开")
            } catch (t: Throwable) {
                CLogUtils.e(TAG, "HiddenApiBypass 初始化失败: ${t.message}", t)
            }
        }

        // 在后台线程预初始化 root shell
        Shell.getShell { shell ->
            // Root shell 已准备好
            CLogUtils.i(TAG, "Root shell 初始化完成: isRoot=${shell.isRoot}")
            if (!shell.isRoot) {
                CLogUtils.w(TAG, "警告: 未获取到Root权限，部分功能可能无法正常工作")
            } else {
                CLogUtils.i(TAG, "成功获取Root权限，全局挂载命名空间已启用")
            }
        }

        com.btrace.viewer.service.MonitoringNotification.ensureChannel(this)

        // Hilt EntryPoint:Application 不能 @Inject 字段,用它取单例做启动初始化。
        val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
            this,
            BTraceAppEntryPoint::class.java
        )

        // spec § 6.4 第 1 档:异步触发 APK 级"接口名 → 包名"反查目录构建。
        // fire-and-forget,不阻塞主线程;builder 内部先 loadFromDisk 让查询立即可用,
        // 再后台增量重建。
        try {
            entryPoint.interfaceIndexBuilder().scanAsync()
        } catch (t: Throwable) {
            CLogUtils.w(TAG, "InterfaceIndexBuilder.scanAsync 触发失败: ${t.message}", t)
        }

        // spec 2026-05-03 § 4.2:App 进入后台时主动 flushNow 持久缓存,把 100ms debounce
        // 窗口缩到最小。挂在 ProcessLifecycleOwner 上,onStop 触发。
        try {
            entryPoint.applicationLifecycleObserver().attach()
        } catch (t: Throwable) {
            CLogUtils.w(TAG, "ApplicationLifecycleObserver.attach 失败: ${t.message}", t)
        }
    }
    
    override fun onTerminate() {
        super.onTerminate()
        CLogUtils.i(TAG, "onTerminate() BTraceApp 结束")
        // 清理Shell实例
        Shell.getShell { shell ->
            shell.close()
        }
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface BTraceAppEntryPoint {
    fun interfaceIndexBuilder(): com.btrace.viewer.parser.InterfaceIndexBuilder
    fun applicationLifecycleObserver(): com.btrace.viewer.parser.ApplicationLifecycleObserver
}
