package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.buildChatRequest
import com.freebuff.core.data.network.parseSseData
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 真实对话流式接入:POST OpenAI 兼容端点(stream=true),按 SSE 行增量解析
 * choices[0].delta.content 并逐个发射文本片段。
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
     * @return 增量文本片段;HTTP/网络失败时抛出异常
     */
    fun chatStream(
        endpoint: String,
        model: String,
        apiKey: String,
        headers: Map<String, String>,
        skipTLS: Boolean,
        history: List<Pair<String, String>>,
    ): Flow<String> = callbackFlow {
        val (request, client) = buildChatRequest(
            endpoint = endpoint,
            model = model,
            apiKey = apiKey,
            headers = headers,
            messages = history,
            stream = true,
            skipTLS = skipTLS,
        )
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
                            parseSseData(line)?.let { trySend(it) }
                        }
                    }
                    close()
                } catch (t: Throwable) {
                    close(t)
                }
            }
        })
        awaitClose { call.cancel() }
    }
}
