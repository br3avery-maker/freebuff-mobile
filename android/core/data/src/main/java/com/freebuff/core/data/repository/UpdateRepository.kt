package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.ApiResult
import com.freebuff.core.data.network.HttpTarget
import com.freebuff.core.data.network.apiCallIo
import com.freebuff.core.data.network.silentRetry
import com.freebuff.core.model.RemoteVersion
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 远程版本检查:GET {updateUrl} 拉取 {version, notes[]},解析为 [RemoteVersion]。
 * 未配置 updateUrl 时返回 [ApiError.NotConfigured],网络/解析失败返回对应分类错误。
 */
@Singleton
class UpdateRepository @Inject constructor(
    @Named("updateUrl") private val updateUrl: String,
    private val client: OkHttpClient,
) {
    val isConfigured: Boolean get() = updateUrl.isNotBlank()

    /** 更新源主机名(如 raw.githubusercontent.com),用于失败提示里指明到底连的哪里。 */
    val sourceLabel: String
        get() = when {
            updateUrl.isBlank() -> ""
            else -> try {
                java.net.URI(updateUrl).host ?: updateUrl
            } catch (t: Throwable) {
                updateUrl
            }
        }

    suspend fun check(): ApiResult<RemoteVersion> {
        if (!isConfigured) return ApiResult.Err(ApiError.NotConfigured("Update source URL"))
        return apiCallIo {
            val req = Request.Builder()
                .url(updateUrl)
                .header("Accept", "application/json")
                .header("User-Agent", "freebuff-mobile")
                .get()
                .build()
            client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                // 上下文标记为更新源:避免 404 报成「请确认 /chat/completions 路径」
                if (!r.isSuccessful) throw ApiError.Http(r.code, body.take(300), HttpTarget.UpdateSource)
                val o = try {
                    JSONObject(body)
                } catch (t: Throwable) {
                    throw ApiError.Parse("Version information is not valid JSON")
                }
                val version = o.optString("version")
                if (version.isBlank()) throw ApiError.Parse("Version information is missing the version field")
                val notes = o.optJSONArray("notes")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
                } ?: emptyList()
                // apk 块是可选扩展:老清单没有它,面板就退化回「前往下载」
                val apk = o.optJSONObject("apk")
                RemoteVersion(
                    version = version,
                    summary = o.optString("summary").trim(),
                    notes = notes,
                    url = o.optString("url"),
                    apkUrl = apk?.optString("url").orEmpty(),
                    apkSha256 = apk?.optString("sha256").orEmpty(),
                    apkSize = apk?.optLong("size") ?: 0L,
                )
            }
        }
    }

    /**
     * 检查更新,瞬时失败(超时/连接/DNS/5xx)静默重试。
     * 保留单次语义的 [check] 不动:单测与「手动重试」按钮都直接用它。
     */
    suspend fun checkWithRetry(attempts: Int = 3): ApiResult<RemoteVersion> =
        silentRetry(attempts) { check() }
}
