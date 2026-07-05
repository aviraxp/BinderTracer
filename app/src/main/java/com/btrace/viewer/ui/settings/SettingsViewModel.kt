package com.btrace.viewer.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.btrace.viewer.BuildConfig
import com.btrace.viewer.data.SettingsRepository
import com.btrace.viewer.data.SocketClient
import com.btrace.viewer.overlay.OverlayPermissionHelper
import com.btrace.viewer.root.RootManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StatusCheckState(
    val isRootAvailable: Boolean? = null,
    val isSocketConnected: Boolean? = null,
    val isChecking: Boolean = false
)

data class SettingsUiState(
    val statusCheck: StatusCheckState = StatusCheckState(),
    val appVersion: String = "1.0.0",
    val toastMessage: String? = null,
    val updateChecking: Boolean = false,
    val updateResult: com.btrace.viewer.utils.UpdateResult? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val rootManager: RootManager,
    private val socketClient: SocketClient,
    private val settingsRepository: SettingsRepository,
    private val updateChecker: com.btrace.viewer.utils.UpdateChecker
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(appVersion = BuildConfig.VERSION_NAME))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val overlayEnabled: StateFlow<Boolean> = settingsRepository.overlayEnabledFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false
    )

    init {
        checkAllStatus()
    }

    fun canDrawOverlays(context: Context): Boolean = OverlayPermissionHelper.canDraw(context)

    /**
     * Switch 切换:
     * - 用户开 + 已有权限 → 持久化 enabled=true
     * - 用户开 + 无权限 → 不持久化,跳系统授权页(用户回来后需再次手动 toggle)
     * - 用户关 → 持久化 enabled=false(无论权限)
     */
    fun setOverlayEnabled(enabled: Boolean, context: Context) {
        if (enabled && !OverlayPermissionHelper.canDraw(context)) {
            OverlayPermissionHelper.openSettings(context)
            return
        }
        viewModelScope.launch {
            settingsRepository.setOverlayEnabled(enabled)
        }
    }

    fun checkAllStatus() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                statusCheck = _uiState.value.statusCheck.copy(isChecking = true)
            )
            val isRoot = rootManager.checkRoot()
            val isSocketConnected = socketClient.isConnected()
            _uiState.value = _uiState.value.copy(
                statusCheck = StatusCheckState(
                    isRootAvailable = isRoot,
                    isSocketConnected = isSocketConnected,
                    isChecking = false
                )
            )
        }
    }

    fun checkRootStatus() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                statusCheck = _uiState.value.statusCheck.copy(isChecking = true)
            )
            val isRoot = rootManager.checkRoot()
            _uiState.value = _uiState.value.copy(
                statusCheck = _uiState.value.statusCheck.copy(
                    isRootAvailable = isRoot,
                    isChecking = false
                )
            )
        }
    }

    fun checkSocketStatus() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                statusCheck = _uiState.value.statusCheck.copy(isChecking = true)
            )
            val isConnected = socketClient.isConnected()
            _uiState.value = _uiState.value.copy(
                statusCheck = _uiState.value.statusCheck.copy(
                    isSocketConnected = isConnected,
                    isChecking = false
                )
            )
        }
    }

    /** 查 GitHub releases/latest 判断是否有更新。结果写入 uiState.updateResult 由 UI 渲染。 */
    fun checkUpdate() {
        if (_uiState.value.updateChecking) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(updateChecking = true, updateResult = null)
            val result = updateChecker.check(_uiState.value.appVersion)
            _uiState.value = _uiState.value.copy(updateChecking = false, updateResult = result)
        }
    }

    fun clearToastMessage() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }
}
