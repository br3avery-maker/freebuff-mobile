package com.freebuff.core.data.network

import com.freebuff.core.model.OfficialModel
import com.freebuff.core.model.modelsUrl
import com.freebuff.core.model.officialModelsFromIds
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Freebuff 官方网关接入。
 *
 * 当前只使用 OpenAI 兼容的只读接口:
 * - `GET {gateway}/v1/models` → 官方模型目录
 *
 * 网关地址由 app 模块的 `DEFAULT_GATEWAY_BASE_URL` 注入;未配置时所有调用返回
 * [ApiError.NotConfigured],官方目录保持为空由 UI 提示「未配置」,不视为错误。
 */
@Singleton
class FreebuffApi @Inject constructor(
    @Named("gatewayBaseUrl") private val gatewayBaseUrl: String,
    private val client: OkHttpClient,
) {
    /** 是否配置了官方网关。 */
    val isConfigured: Boolean get() = gatewayBaseUrl.isNotBlank()

    /** 拉取官方模型目录。成功且非空时返回网关目录,失败返回分类错误。 */
    suspend fun fetchOfficialModels(): ApiResult<List<OfficialModel>> {
        if (!isConfigured) return ApiResult.Err(ApiError.NotConfigured("Built-in gateway URL"))
        val url = modelsUrl(gatewayBaseUrl)
        if (url.isBlank()) return ApiResult.Err(ApiError.NotConfigured("Built-in gateway URL"))
        return apiCallIo {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiError.Http(r.code, body.take(300))
                val ids = parseModelIds(body)
                if (ids.isEmpty()) throw ApiError.Parse("Model list is empty or missing id fields")
                officialModelsFromIds(ids)
            }
        }
    }

    companion object {
        const val USER_AGENT = "freebuff-mobile"
    }
}

/**
 * 解析 /v1/models 响应,兼容三种常见结构:
 * - `{"data":[{"id":"..."}]}`(OpenAI)
 * - `{"models":[{"id":"..."}] | ["..."]}`
 * - `["..."]` 纯数组
 */
fun parseModelIds(body: String): List<String> {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return emptyList()
    return try {
        when (trimmed.first()) {
            '[' -> parseIdArray(JSONArray(trimmed))
            '{' -> {
                val o = JSONObject(trimmed)
                val arr = o.optJSONArray("data") ?: o.optJSONArray("models") ?: o.optJSONArray("model_ids")
                if (arr == null) emptyList() else parseIdArray(arr)
            }
            else -> emptyList()
        }
    } catch (t: Throwable) {
        emptyList()
    }
}

private fun parseIdArray(arr: JSONArray): List<String> =
    (0 until arr.length()).mapNotNull { i ->
        when (val item = arr.opt(i)) {
            is JSONObject -> item.optString("id").ifBlank { item.optString("name") }.takeIf { it.isNotBlank() }
            is String -> item.takeIf { it.isNotBlank() }
            else -> null
        }
    }
