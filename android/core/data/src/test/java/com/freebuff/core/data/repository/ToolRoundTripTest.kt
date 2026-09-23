package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ChatMessage
import com.freebuff.core.model.AgentEvent
import com.freebuff.core.model.DefaultTools
import com.freebuff.core.model.ToolCallReq
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Agent 循环的请求侧契约:tools 定义、assistant tool_calls 与 tool 结果消息按 OpenAI 协议序列化。 */
class ToolRoundTripTest {
    private lateinit var server: MockWebServer
    private val repo = ChatRepository()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `请求体携带 tools 定义与 tool 结果消息`() = runTest {
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n"))
        repo.chatStream(
            endpoint = server.url("/v1").toString(),
            model = "m",
            apiKey = "k",
            headers = emptyMap(),
            skipTLS = false,
            history = listOf(
                ChatMessage.text("user", "搜一下 freebuff"),
                ChatMessage.assistantWithCalls(
                    "",
                    listOf(ToolCallReq("call-1", "web_search", """{"query":"freebuff"}""")),
                ),
                ChatMessage.toolResult("call-1", "搜索结果:…"),
            ),
            toolsJson = DefaultTools.toJsonArrayString(),
        ).toList()

        val body = JSONObject(server.takeRequest().body.readUtf8())
        // tools 数组在请求体中
        val tools = body.getJSONArray("tools")
        assertEquals(DefaultTools.ALL.size, tools.length())
        assertEquals("web_search", tools.getJSONObject(0).getJSONObject("function").getString("name"))
        // 消息序列:user → assistant(带 tool_calls)→ tool(带 tool_call_id)
        val msgs = body.getJSONArray("messages")
        assertEquals(3, msgs.length())
        assertEquals("user", msgs.getJSONObject(0).getString("role"))
        val asst = msgs.getJSONObject(1)
        assertEquals("assistant", asst.getString("role"))
        assertEquals("call-1", asst.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("web_search", asst.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"))
        val toolMsg = msgs.getJSONObject(2)
        assertEquals("tool", toolMsg.getString("role"))
        assertEquals("call-1", toolMsg.getString("tool_call_id"))
        assertEquals("搜索结果:…", toolMsg.getString("content"))
    }

    @Test
    fun `流中的 tool_calls 增量经 Calls 事件完整吐出`() = runTest {
        // 参数分两片到达 + finish_reason=tool_calls 落定
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-9\",\"function\":{\"name\":\"calculator\",\"arguments\":\"{\\\"expr\"}}]}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"ession\\\":\\\"1+2\\\"}\"}}]}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        val events = repo.chatStream(
            endpoint = server.url("/v1").toString(),
            model = "m",
            apiKey = "k",
            headers = emptyMap(),
            skipTLS = false,
            history = listOf(ChatMessage.text("user", "算 1+2")),
            toolsJson = DefaultTools.toJsonArrayString(),
        ).toList()

        val calls = events.filterIsInstance<AgentEvent.Calls>().single().calls
        assertEquals(1, calls.size)
        assertEquals("call-9", calls[0].callId)
        assertEquals("calculator", calls[0].name)
        assertTrue(JSONObject(calls[0].argsJson).getString("expression") == "1+2")
    }
}
