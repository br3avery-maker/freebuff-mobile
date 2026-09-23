package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.ApiResult
import com.freebuff.core.data.network.apiCallIo
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

    suspend fun check(): ApiResult<RemoteVersion> {
        if (!isConfigured) return ApiResult.Err(ApiError.NotConfigured("更新源地址"))
        return apiCallIo {
            val req = Request.Builder()
                .url(updateUrl)
                .header("Accept", "application/json")
                .header("User-Agent", "freebuff-mobile")
                .get()
                .build()
            client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiError.Http(r.code, body.take(300))
                val o = try {
                    JSONObject(body)
                } catch (t: Throwable) {
                    throw ApiError.Parse("版本信息不是合法 JSON")
                }
                val version = o.optString("version")
                if (version.isBlank()) throw ApiError.Parse("版本信息缺少 version 字段")
                val notes = o.optJSONArray("notes")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
                } ?: emptyList()
                RemoteVersion(version = version, notes = notes, url = o.optString("url"))
            }
        }
    }
}
