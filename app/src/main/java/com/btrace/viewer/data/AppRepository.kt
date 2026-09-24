package com.btrace.viewer.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.btrace.viewer.model.AppInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用信息仓库
 * 负责获取设备上已安装的应用列表
 */
@Singleton
class AppRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val packageManager: PackageManager = context.packageManager

    /**
     * 获取所有已安装的用户应用
     * @param includeSystemApps 是否包含系统应用
     */
    suspend fun getInstalledApps(includeSystemApps: Boolean = false): List<AppInfo> = 
        withContext(Dispatchers.IO) {
            val apps = mutableListOf<AppInfo>()
            
            val installedPackages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            
            for (appInfo in installedPackages) {
                // 过滤系统应用
                if (!includeSystemApps && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                    continue
                }
                
                try {
                    val appName = packageManager.getApplicationLabel(appInfo).toString()
                    val icon = try {
                        packageManager.getApplicationIcon(appInfo)
                    } catch (e: Exception) {
                        null
                    }
                    
                    apps.add(
                        AppInfo(
                            packageName = appInfo.packageName,
                            appName = appName,
                            uid = appInfo.uid,
                            icon = icon
                        )
                    )
                } catch (e: Exception) {
                    // 跳过无法获取信息的应用
                }
            }
            
            // 按应用名排序
            apps.sortedBy { it.appName.lowercase() }
        }

    /**
     * 根据UID获取包名
     */
    fun getPackageNameForUid(uid: Int): String? {
        return packageManager.getPackagesForUid(uid)?.firstOrNull()
    }

    /**
     * 反查 uid 对应的「显示名」:系统 uid 优先走 [SystemUidNames] 助记名,
     * 其余 uid 只在 PackageManager 返回唯一包名时使用,无法唯一确定则返回 null。
     *
     * 用途:request 帧的 toUid 反查目标进程/服务的可读标签。系统 binder 服务
     * (system_server / audioserver / surfaceflinger 等)走 system uid 区间。
     * UID 1000 也可能查出多个系统应用,这些包不能代表 system_server。
     * getPackagesForUid 返回的是共享该 uid 的所有包,并不表示当前接收者:
     * frameworks/base/core/java/android/content/pm/PackageManager.java
     * (android-14.0.0_r1, lines 6663-6676)。
     */
    fun getPackageOrSystemNameForUid(uid: Int): String? {
        if (uid <= 0) return null
        return SystemUidNames.lookup(uid) ?: packageManager.getPackagesForUid(uid)?.singleOrNull()
    }

    /**
     * 根据UID获取应用名
     *
     * spec 2026-05-03 § 4.2 PM 边界:本方法调 getApplicationInfo 仅用于拿 ApplicationLabel
     * 显示给 UI,**不**读 versionCode/lastUpdateTime。spec §4.2 末尾"全 spec 调
     * PackageManager 拿版本元数据的位置只有...两处"的不变量未被破坏 ——
     * 拿"版本元数据"仍只在 AppClassLoaderRegistry 内部。
     */
    fun getAppNameForUid(uid: Int): String {
        val packageName = getPackageNameForUid(uid) ?: return "UID:$uid"
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    /**
     * 搜索应用
     */
    suspend fun searchApps(query: String, includeSystemApps: Boolean = false): List<AppInfo> {
        val allApps = getInstalledApps(includeSystemApps)
        if (query.isBlank()) return allApps
        
        val lowerQuery = query.lowercase()
        return allApps.filter { app ->
            app.appName.lowercase().contains(lowerQuery) ||
            app.packageName.lowercase().contains(lowerQuery)
        }
    }
}
