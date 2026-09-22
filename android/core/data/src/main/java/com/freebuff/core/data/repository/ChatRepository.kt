package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.AgentEventParser
import com.freebuff.core.data.network.buildChatRequest
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
     * @param history 历史消息 (role, content)
     * @return 事件流,按到达顺序;HTTP/网络失败时抛出异常
     */
    fun chatStream(
        endpoint: String,
        model: String,
        apiKey: String,
        headers: Map<String, String>,
        skipTLS: Boolean,
        history: List<Pair<String, String>>,
    ): Flow<AgentEvent> = callbackFlow {
        val (request, client) = buildChatRequest(
            endpoint = endpoint,
            model = model,
            apiKey = apiKey,
            headers = headers,
            messages = history,
            stream = true,
            skipTLS = skipTLS,
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
                    close()
                } catch (t: Throwable) {
                    close(t)
                }
            }
        })
        awaitClose { call.cancel() }
    }
}
