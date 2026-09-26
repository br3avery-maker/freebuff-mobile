package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.AgentEventParser
import com.freebuff.core.data.network.ChatMessage
import com.freebuff.core.data.network.DEFAULT_STREAM_IDLE_TIMEOUT_MS
import com.freebuff.core.data.network.RequestFallbacks
import com.freebuff.core.data.network.SseDecoder
import com.freebuff.core.data.network.buildChatRequest
import com.freebuff.core.data.network.idleWatchdog
import com.freebuff.core.model.AgentEvent
import com.freebuff.core.model.ReasoningPlan
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 真实对话流式接入:POST OpenAI 兼容端点(stream=true),按 **SSE 帧** 增量解析。
 *
 * 解析:交给 [SseDecoder](跨行 data / 末尾事件 / 注释心跳 / NDJSON 都覆盖)与
 * [AgentEventParser](工具调用分片、字段名差异、错误帧)。
 *
 * 请求侧降级(工具注入适配,失败只降一次):
 * - 端点返回「不接受 tools」类错误 → 去掉 tools 重发一次,并提示用户纯对话继续;
 * - 端点返回「不认识思考参数」类错误 → 去掉思考字段重发一次(思考内容仍照常展示)。
 *
 * 事件流与 agent-architecture.md §4 契约对应;解析失败/未知结构静默跳过。
 */
@Singleton
class ChatRepository @Inject constructor() {

    /**
     * 发起流式对话。
     * @param endpoint 用户配置的端点(如 https://host/v1, 或完整 /chat/completions)
     * @param model 模型 id
     * @param apiKey Bearer Key(可为空)
     * @param headers 附加请求头(JSON 解析后的键值)
     * @param skipTLS 是否跳过 TLS 校验
     * @param history 历史消息(文本/工具调用/工具结果混合)
     * @param toolsJson OpenAI 兼容 tools 数组;空串表示不启用 function calling
     * @param reasoning 思维链参数(按模型识别,见 core:model 的 Reasoning);null 表示不送
     * @param idleTimeoutMs 流式空闲看门狗阈值:连续这么久没有任何事件到达则以
     *   [ApiError.StreamIdle] 终止(防「连接存活但不吐数据」的无限等待);<= 0 关闭
     * @return 事件流,按到达顺序;HTTP/网络失败时抛出异常
     */
    fun chatStream(
        endpoint: String,
        model: String,
        apiKey: String,
        headers: Map<String, String>,
        skipTLS: Boolean,
        history: List<ChatMessage>,
        toolsJson: String = "",
        reasoning: ReasoningPlan? = null,
        idleTimeoutMs: Long = DEFAULT_STREAM_IDLE_TIMEOUT_MS,
    ): Flow<AgentEvent> = callbackFlow {
        // 降级开关:工具/思考字段各只降一次,避免「降级→再被拒→再降级」的循环
        var allowToolFallback = toolsJson.isNotBlank()
        var allowReasoningFallback = reasoning != null
        var currentCall: Call? = null

        fun buildReq(dropTools: Boolean, dropReasoning: Boolean): Pair<Request, OkHttpClient> = buildChatRequest(
            endpoint = endpoint,
            model = model,
            apiKey = apiKey,
            headers = headers,
            messages = history,
            stream = true,
            skipTLS = skipTLS,
            toolsJson = if (dropTools) "" else toolsJson,
            reasoning = if (dropReasoning) null else reasoning,
        )

        // 逐行读响应体 → 解码 SSE 帧 → 解析事件。流结束(或连接断开)时收尾。
        fun pump(response: Response, parser: AgentEventParser) {
            response.body?.source()?.use { source ->
                val decoder = SseDecoder()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    for (payload in decoder.feed(line)) {
                        if (payload.trim() == "[DONE]") continue
                        parser.feed(payload).forEach { trySend(it) }
                    }
                }
                // 末尾事件可能没有空行收尾:必须 flush(SseDecoder 文档里有上游客的成因)
                for (payload in decoder.flush()) {
                    if (payload.trim() == "[DONE]") continue
                    parser.feed(payload).forEach { trySend(it) }
                }
            }
            parser.flush().forEach { trySend(it) }
            val calls = parser.drainCalls()
            if (calls.isNotEmpty()) trySend(AgentEvent.Calls(calls))
            close()
        }

        fun start(req: Request, client: OkHttpClient) {
            val call = client.newCall(req)
            currentCall = call
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    close(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (response.isSuccessful) {
                            pump(response, AgentEventParser())
                            return
                        }
                        val body = response.body?.string().orEmpty()
                        // 端点不接受工具定义 / 思考参数:去掉该字段重发一次并提示用户
                        if (allowToolFallback && RequestFallbacks.toolsRejected(response.code, body)) {
                            allowToolFallback = false
                            trySend(AgentEvent.Notice(RequestFallbacks.TOOLS_DROPPED_NOTE, AgentEvent.Notice.Kind.TOOLS_DROPPED))
                            val (r, c) = buildReq(dropTools = true, dropReasoning = false)
                            start(r, c)
                            return
                        }
                        if (allowReasoningFallback && RequestFallbacks.reasoningRejected(response.code, body)) {
                            allowReasoningFallback = false
                            trySend(AgentEvent.Notice(RequestFallbacks.REASONING_DROPPED_NOTE, AgentEvent.Notice.Kind.REASONING_DROPPED))
                            val (r, c) = buildReq(dropTools = false, dropReasoning = true)
                            start(r, c)
                            return
                        }
                        close(ApiError.Http(response.code, body.take(300)))
                    } catch (t: Throwable) {
                        close(t)
                    }
                }
            })
        }

        val (request, client) = buildReq(dropTools = false, dropReasoning = false)
        start(request, client)
        awaitClose { currentCall?.cancel() }
    }.idleWatchdog(idleTimeoutMs)
}
