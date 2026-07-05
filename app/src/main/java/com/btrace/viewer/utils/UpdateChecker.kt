package com.btrace.viewer.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/** 检查更新的结果。UI 据此渲染,不在 ViewModel 里拼文案。 */
sealed interface UpdateResult {
    /** 发现新版本。[version] 是 release 的 tag(如 v1.2.0),[url] 是 release 页,[notes] 是标题。 */
    data class Available(val version: String, val url: String, val notes: String) : UpdateResult
    /** 已是最新。 */
    object UpToDate : UpdateResult
    /** 仓库尚未发布任何 release(API 404)。 */
    object NoRelease : UpdateResult
    /** 网络/解析失败。 */
    data class Error(val message: String) : UpdateResult
}

/**
 * 查 GitHub `releases/latest` 判断是否有更新。用 stdlib [HttpURLConnection] + org.json,
 * 不引第三方 HTTP 库。
 */
class UpdateChecker @Inject constructor() {

    companion object {
        private const val RELEASES_API =
            "https://api.github.com/repos/Anekys/BinderTracer/releases/latest"
        private const val RELEASES_PAGE = "https://github.com/Anekys/BinderTracer/releases"
        private const val TIMEOUT_MS = 8000

        /**
         * remote tag 是否比 local 版本新。按 (major, minor, patch) 三元组比较;三元组相同时,
         * local 是预发布(版本串含 `-`,如 `1.0.0-dev`)而 remote 不是 → 正式版更新。
         * 无法解析出三元组的当 0.0.0(异常输入不误报有更新)。
         */
        fun isNewer(local: String, remoteTag: String): Boolean {
            val (lNums, lPre) = parse(local)
            val (rNums, rPre) = parse(remoteTag)
            for (i in 0..2) if (rNums[i] != lNums[i]) return rNums[i] > lNums[i]
            return lPre && !rPre
        }

        /** 抽出 `X.Y.Z` 三元组 + 是否预发布(含 `-` 后缀)。 */
        private fun parse(v: String): Pair<IntArray, Boolean> {
            val m = Regex("""(\d+)\.(\d+)\.(\d+)""").find(v)
            val nums = if (m != null) {
                intArrayOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            } else {
                intArrayOf(0, 0, 0)
            }
            return nums to v.contains('-')
        }
    }

    suspend fun check(currentVersion: String): UpdateResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "BinderTracer-App")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            when (conn.responseCode) {
                200 -> {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val tag = json.getString("tag_name")
                    val url = json.optString("html_url", RELEASES_PAGE)
                    val notes = json.optString("name", tag)
                    if (isNewer(currentVersion, tag)) UpdateResult.Available(tag, url, notes)
                    else UpdateResult.UpToDate
                }
                404 -> UpdateResult.NoRelease
                else -> UpdateResult.Error("HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            UpdateResult.Error(e.message ?: "网络错误")
        } finally {
            conn?.disconnect()
        }
    }
}
