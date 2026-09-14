package com.freebuff.core.data.network

import com.freebuff.core.model.endpointUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import timber.log.Timber

/** 单个自定义模型的测试连接结果。 */
sealed interface ProbeResult {
    /**
     * 连接成功。
     * @param ms 往返耗时(毫秒)
     * @param note 非致命提示(如连接成功但模型快照刷新失败),空串表示无
     */
    data class Success(val ms: Int, val note: String = "") : ProbeResult
    data class Fail(val reason: String, val code: Int? = null) : ProbeResult
}

/** 生产 OkHttp 客户端(默认超时 30s,release 关闭日志)。 */
fun buildDefaultClient(): OkHttpClient {
    val logging = HttpLoggingInterceptor { msg -> Timber.d(msg) }
    logging.level = HttpLoggingInterceptor.Level.BASIC
    return OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(logging)
        .build()
}

/** 按 skipTLS 构建客户端:true 时信任任意证书(仅用于用户显式开启的模型)。 */
fun clientFor(skipTLS: Boolean, base: OkHttpClient = buildDefaultClient()): OkHttpClient {
    if (!skipTLS) return base
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    }
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAll), java.security.SecureRandom())
    }
    return base.newBuilder()
        .sslSocketFactory(sslContext.socketFactory, trustAll)
        .hostnameVerifier { _, _ -> true }
        .build()
}

/** 构造 OpenAI 兼容聊天请求。 */
fun buildChatRequest(
    endpoint: String,
    model: String,
    apiKey: String,
    headers: Map<String, String>,
    messages: List<Pair<String, String>>,
    stream: Boolean,
    skipTLS: Boolean,
): Pair<Request, OkHttpClient> {
    val body = JSONObject()
        .put("model", model)
        .put("stream", stream)
        .put("messages", JSONArray().apply {
            messages.forEach { (role, content) ->
                put(JSONObject().put("role", role).put("content", content))
            }
        })
        .toString()
    val builder = Request.Builder()
        .url(endpointUrl(endpoint))
        .post(body.toRequestBody("application/json".toMediaType()))
    if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")
    headers.filter { (k, _) -> k.isNotBlank() }.forEach { (k, v) -> builder.addHeader(k, v) }
    return builder.build() to clientFor(skipTLS)
}

/**
 * 解析一行 SSE 文本。
 * - 非 "data:" 前缀行返回 null(如注释/空行)
 * - "data: [DONE]" 返回 null(流结束)
 * - OpenAI 兼容增量取 choices[0].delta.content;兼容取 choices[0].text / message.content
 * - 未知结构返回空串(不影响流)
 */
fun parseSseData(line: String): String? {
    if (!line.startsWith("data:")) return null
    val data = line.removePrefix("data:").trim()
    if (data.isEmpty() || data == "[DONE]") return null
    val obj = try {
        JSONObject(data)
    } catch (e: Exception) {
        return null
    }
    val choices = obj.optJSONArray("choices") ?: return ""
    val c = choices.optJSONObject(0) ?: return ""
    val delta = c.optJSONObject("delta")
    if (delta != null && delta.has("content") && !delta.isNull("content")) {
        return delta.optString("content")
    }
    if (c.has("text") && !c.isNull("text")) return c.optString("text")
    if (c.has("message")) {
        val m = c.optJSONObject("message")
        if (m != null && m.has("content") && !m.isNull("content")) return m.optString("content")
    }
    return ""
}

/**
 * 把异常/HTTP 码映射成可读文案。
 * 仅保留为兼容入口,分类逻辑已统一收敛到 [toApiError] / [httpErrorMessage]。
 */
fun mapChatError(t: Throwable, code: Int? = null): String = t.toApiError(code).userMessage
