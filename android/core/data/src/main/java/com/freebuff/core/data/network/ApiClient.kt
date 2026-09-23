package com.freebuff.core.data.network

import com.freebuff.core.model.ToolCallReq
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

/**
 * 一条对话消息:普通文本消息或工具结果消息。
 * - 文本:content 即文本,toolCalls 为空
 * - assistant 触发工具:content=文本部分(可空),toolCalls=模型请求的调用列表(序列化为 tool_calls)
 * - 工具结果:role 固定 "tool",content=结果文本,toolCallId=对应调用 id
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val toolCalls: List<ToolCallReq> = emptyList(),
    val toolCallId: String = "",
) {
    companion object {
        fun text(role: String, content: String) = ChatMessage(role, content)
        fun toolResult(callId: String, content: String) = ChatMessage("tool", content, toolCallId = callId)
        fun assistantWithCalls(content: String, calls: List<ToolCallReq>) =
            ChatMessage("assistant", content, toolCalls = calls)
    }
}

/** 构造 OpenAI 兼容聊天请求。toolsJson 非空时附带 function calling 工具定义。 */
fun buildChatRequest(
    endpoint: String,
    model: String,
    apiKey: String,
    headers: Map<String, String>,
    messages: List<ChatMessage>,
    stream: Boolean,
    skipTLS: Boolean,
    toolsJson: String = "",
): Pair<Request, OkHttpClient> {
    val body = JSONObject()
        .put("model", model)
        .put("stream", stream)
        .put("messages", JSONArray().apply {
            messages.forEach { m ->
                val o = JSONObject().put("role", m.role).put("content", m.content)
                if (m.role == "assistant" && m.toolCalls.isNotEmpty()) {
                    o.put("tool_calls", JSONArray().apply {
                        m.toolCalls.forEach { c ->
                            put(JSONObject()
                                .put("id", c.callId)
                                .put("type", "function")
                                .put("function", JSONObject().put("name", c.name).put("arguments", c.argsJson.ifBlank { "{}" })))
                        }
                    })
                }
                if (m.role == "tool") {
                    o.put("tool_call_id", m.toolCallId)
                }
                put(o)
            }
        })
    if (toolsJson.isNotBlank()) body.put("tools", JSONArray(toolsJson))
    val json = body.toString()
    val builder = Request.Builder()
        .url(endpointUrl(endpoint))
        .post(json.toRequestBody("application/json".toMediaType()))
    if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")
    headers.filter { (k, _) -> k.isNotBlank() }.forEach { (k, v) -> builder.addHeader(k, v) }
    return builder.build() to clientFor(skipTLS)
}

/**
 * 提取一行 SSE 文本的 data 载荷(原始 JSON 字符串,不做内容解析)。
 * - 非 "data:" 前缀行返回 null(如注释/空行)
 * - "data: [DONE]" 返回 null(流结束)
 * - 载荷本体交给 [AgentEventParser] 做结构化解析(文本/工具/事件帧)
 */
fun parseSseData(line: String): String? {
    if (!line.startsWith("data:")) return null
    val data = line.removePrefix("data:").trim()
    return if (data.isEmpty() || data == "[DONE]") null else data
}

/**
 * 把异常/HTTP 码映射成可读文案。
 * 仅保留为兼容入口,分类逻辑已统一收敛到 [toApiError] / [httpErrorMessage]。
 */
fun mapChatError(t: Throwable, code: Int? = null): String = t.toApiError(code).userMessage
