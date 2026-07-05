package com.btrace.viewer.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.btrace.viewer.utils.CLogUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "btrace_settings")

/**
 * 用户配置持久化。
 *
 * 当前承担"事件缓存上限" + "悬浮窗开关"。后续可继续加(过滤预设等)。
 *
 * 用 DataStore Preferences(已在 build.gradle 依赖里)。读取暴露为 Flow,写入是 suspend。
 * 不缓存内存副本 —— 让 caller 用 [first] 或 collect 自己定。
 */
@Singleton
class SettingsRepository @Inject constructor(private val context: Context) {

    companion object {
        private const val TAG = "SettingsRepository"
        val KEY_OVERLAY_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("overlay_enabled")
    }

    val overlayEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_OVERLAY_ENABLED] ?: false
    }

    suspend fun setOverlayEnabled(enabled: Boolean) {
        CLogUtils.i(TAG, "setOverlayEnabled() persist $enabled")
        context.dataStore.edit { prefs ->
            prefs[KEY_OVERLAY_ENABLED] = enabled
        }
    }
}
