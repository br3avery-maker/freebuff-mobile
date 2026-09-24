package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.AgentEventParser
import com.freebuff.core.data.network.ChatMessage
import com.freebuff.core.data.network.buildChatRequest
import com.freebuff.core.data.network.DEFAULT_STREAM_IDLE_TIMEOUT_MS
import com.freebuff.core.data.network.idleWatchdog
import com.freebuff.core.data.network.parseSseData
import com.freebuff.core.model.AgentEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 真实对话流式接入:POST OpenAI 兼容端点(stream=true),按 SSE 行增量解析。
 *
 * 发射 [AgentEvent] 事件流(与 agent-architecture.md §4 契约对应):
 * - 文本增量(choices[0].delta.content)
 * - 工具调用/结果(delta.tool_calls 参数累积,finish_reason=tool_calls 时落卡)
 * - Freebuff 原生事件帧(type=tool_call/tool_result/error/subagent-* / end_turn)
 * 事件按到达顺序发射;解析失败/未知结构静默跳过,不影响流。
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
        idleTimeoutMs: Long = DEFAULT_STREAM_IDLE_TIMEOUT_MS,
    ): Flow<AgentEvent> = callbackFlow {
        val (request, client) = buildChatRequest(
            endpoint = endpoint,
            model = model,
            apiKey = apiKey,
            headers = headers,
            messages = history,
            stream = true,
            skipTLS = skipTLS,
            toolsJson = toolsJson,
        )
        val parser = AgentEventParser()
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        val snippet = response.body?.string()?.take(300) ?: ""
                        close(ApiError.Http(response.code, snippet))
                        return
                    }
                    response.body?.source()?.use { source ->
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            val data = parseSseData(line) ?: continue
                            parser.feed(data).forEach { trySend(it) }
                        }
                    }
                    // 流正常结束:把参数累积未落定的工具调用 flush 出来
                    // (部分端点不发 finish_reason=tool_calls)
                    parser.flush().forEach { trySend(it) }
                    // 把结构化调用交给 agent 循环(执行→回传→继续对话)
                    val calls = parser.drainCalls()
                    if (calls.isNotEmpty()) trySend(AgentEvent.Calls(calls))
                    close()
                } catch (t: Throwable) {
                    close(t)
                }
            }
        })
        awaitClose { call.cancel() }
    }.idleWatchdog(idleTimeoutMs)
}
