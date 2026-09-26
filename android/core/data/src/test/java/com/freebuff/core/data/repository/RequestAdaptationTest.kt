package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ChatMessage
import com.freebuff.core.data.network.buildChatRequest
import com.freebuff.core.model.Reasoning
import com.freebuff.core.model.ReasoningPlan
import com.freebuff.core.model.ToolCallReq
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 请求侧适配:空 content 的处理、thinking 与工具的历史冲突、工具参数永不为空串。 */
class RequestAdaptationTest {

    private fun body(messages: List<ChatMessage>, reasoning: ReasoningPlan?): JSONObject {
        val (req, _) = buildChatRequest(
            endpoint = "https://example.com/v1",
            model = "claude-3-7-sonnet",
            apiKey = "k",
            headers = emptyMap(),
            messages = messages,
            stream = true,
            skipTLS = false,
            toolsJson = """[{"type":"function","function":{"name":"current_time"}}]""",
            reasoning = reasoning,
        )
        val buf = okio.Buffer()
        req.body!!.writeTo(buf)
        return JSONObject(buf.readUtf8())
    }

    @Test
    fun `纯工具调用的 assistant 消息不带空 content`() {
        // 严格实现会因为 content:"" + tool_calls 直接 400
        val b = body(
            listOf(
                ChatMessage.text("user", "现在几点"),
                ChatMessage.assistantWithCalls("", listOf(ToolCallReq("c1", "current_time", "{}"))),
                ChatMessage.toolResult("c1", "12:00"),
            ),
            reasoning = null,
        )
        val msgs = b.getJSONArray("messages")
        val assistant = msgs.getJSONObject(1)
        assertFalse("空 content 不该出现", assistant.has("content"))
        assertEquals(1, assistant.getJSONArray("tool_calls").length())
        assertEquals("tool", msgs.getJSONObject(2).optString("role"))
        assertEquals("c1", msgs.getJSONObject(2).optString("tool_call_id"))
    }

    @Test
    fun `assistant 带正文时 content 照常携带`() {
        val b = body(
            listOf(ChatMessage.assistantWithCalls("我先看时间", listOf(ToolCallReq("c1", "current_time", "{}")))),
            reasoning = null,
        )
        assertEquals("我先看时间", b.getJSONArray("messages").getJSONObject(0).optString("content"))
    }

    @Test
    fun `工具参数为空串时回传为 {}`() {
        val b = body(
            listOf(ChatMessage.assistantWithCalls("", listOf(ToolCallReq("c1", "current_time", "")))),
            reasoning = null,
        )
        val fn = b.getJSONArray("messages").getJSONObject(0)
            .getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function")
        assertEquals("{}", fn.optString("arguments"))
    }

    @Test
    fun `thinking 参数在首轮发送`() {
        val b = body(
            listOf(ChatMessage.text("user", "你好")),
            ReasoningPlan(com.freebuff.core.model.ReasoningFlavor.THINKING_BUDGET),
        )
        assertTrue(b.has("thinking"))
        assertEquals(Reasoning.BUDGET_TOKENS, b.getJSONObject("thinking").optInt("budget_tokens"))
    }

    @Test
    fun `历史里已有带工具调用的 assistant 消息时丢掉 thinking`() {
        // Anthropic 兼容 + 工具的冲突:LiteLLM 的 modify_params 就是「缺 thinking_blocks 就丢 thinking」,
        // 否则第二轮必然 400(OpenAI 兼容协议里没有 thinking_blocks 可回传)
        val b = body(
            listOf(
                ChatMessage.text("user", "现在几点"),
                ChatMessage.assistantWithCalls("", listOf(ToolCallReq("c1", "current_time", "{}"))),
                ChatMessage.toolResult("c1", "12:00"),
            ),
            ReasoningPlan(com.freebuff.core.model.ReasoningFlavor.THINKING_BUDGET),
        )
        assertFalse("带工具调用的轮次不能再送 thinking", b.has("thinking"))
        assertTrue("工具定义仍在", b.has("tools"))
    }

    @Test
    fun `Qwen 式 enable_thinking 不受工具历史影响`() {
        val b = body(
            listOf(ChatMessage.assistantWithCalls("", listOf(ToolCallReq("c1", "current_time", "{}")))),
            ReasoningPlan(com.freebuff.core.model.ReasoningFlavor.ENABLE_THINKING),
        )
        assertTrue(b.optBoolean("enable_thinking"))
    }
}
