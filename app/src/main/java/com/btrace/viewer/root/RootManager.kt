package com.btrace.viewer.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Root权限管理器
 * 负责检查和获取Root权限，使用libsu库和全局挂载命名空间
 */
@Singleton
class RootManager @Inject constructor() {

    companion object {
        private const val TAG = "RootManager"
    }

    private var shellInstance: Shell? = null
    
    /**
     * 获取或创建Shell实例，使用全局挂载命名空间
     */
    private suspend fun getShell(): Shell = withContext(Dispatchers.IO) {
        shellInstance ?: run {
            val builder = Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
            
            val shell = builder.build()
            if (shell.isRoot) {
                shellInstance = shell
                shell
            } else {
                throw SecurityException("未获取到Root权限")
            }
        }
    }

    /**
     * 检查设备是否已获取Root权限
     * @return true 如果已获取Root权限
     */
    suspend fun checkRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            val shell = getShell()
            shell.isRoot
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 执行需要Root权限的命令
     * @param command 要执行的命令
     * @return 命令执行结果
     */
    suspend fun executeRootCommand(command: String): Shell.Result = withContext(Dispatchers.IO) {
        val shell = getShell()
        shell.newJob().add(command).exec()
    }

    /**
     * 在全局挂载命名空间中执行命令
     * @param command 要执行的命令
     * @return 命令执行结果
     */
    suspend fun executeInGlobalMountNamespace(command: String): Shell.Result = withContext(Dispatchers.IO) {
        val shell = getShell()
        // Shell.FLAG_MOUNT_MASTER 确保在全局挂载命名空间中执行
        shell.newJob().add(command).exec()
    }

    /**
     * 清理Shell实例
     */
    fun cleanup() {
        shellInstance?.close()
        shellInstance = null
    }
}
