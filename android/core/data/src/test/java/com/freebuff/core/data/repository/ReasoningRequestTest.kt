package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ChatMessage
import com.freebuff.core.model.Reasoning
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 请求体里的思维链参数:按模型族只送该族认得的字段,不认识的模型一个字段都不加。 */
class ReasoningRequestTest {
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

    /** 发一次流式请求,返回真实发出去的请求体 JSON。 */
    private suspend fun bodyFor(modelId: String, mode: String): JSONObject {
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n"))
        repo.chatStream(
            endpoint = server.url("/v1").toString(),
            model = modelId,
            apiKey = "",
            headers = emptyMap(),
            skipTLS = false,
            history = listOf(ChatMessage.text("user", "hi")),
            reasoning = Reasoning.plan(mode, modelId),
            idleTimeoutMs = 0,
        ).toList()
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    @Test
    fun `OpenAI 推理模型带上 effort 与补全预算`() = runTest {
        val body = bodyFor("gpt-5-codex", Reasoning.MODE_AUTO)
        assertEquals("medium", body.getString("reasoning_effort"))
        // 思考 token 记在这份预算里:不抬高就会被「想完没额度」截断成几句话
        assertEquals(Reasoning.MAX_COMPLETION_TOKENS, body.getInt("max_completion_tokens"))
    }

    @Test
    fun `Qwen 系带 enable_thinking`() = runTest {
        val body = bodyFor("qwen3-235b", Reasoning.MODE_AUTO)
        assertTrue(body.getBoolean("enable_thinking"))
        assertFalse(body.has("reasoning_effort"))
        assertFalse(body.has("max_completion_tokens"))
    }

    @Test
    fun `Anthropic 兼容与 GLM 带 thinking 块`() = runTest {
        val body = bodyFor("glm-4.6", Reasoning.MODE_AUTO)
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        assertEquals(Reasoning.BUDGET_TOKENS, body.getJSONObject("thinking").getInt("budget_tokens"))
    }

    @Test
    fun `默认就思考的模型不送任何参数`() = runTest {
        val body = bodyFor("deepseek-reasoner", Reasoning.MODE_AUTO)
        assertFalse(body.has("reasoning_effort"))
        assertFalse(body.has("enable_thinking"))
        assertFalse(body.has("thinking"))
        assertFalse(body.has("max_completion_tokens"))
    }

    @Test
    fun `关闭深度思考时不送参数`() = runTest {
        val body = bodyFor("gpt-5-codex", Reasoning.MODE_OFF)
        assertFalse(body.has("reasoning_effort"))
        assertFalse(body.has("max_completion_tokens"))
        // 基本字段不受影响
        assertEquals("gpt-5-codex", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
    }

    @Test
    fun `未识别模型在自动模式下不冒险送字段,强制开启才送 enable_thinking`() = runTest {
        val auto = bodyFor("my-private-model", Reasoning.MODE_AUTO)
        assertFalse(auto.has("enable_thinking"))
        assertFalse(auto.has("reasoning_effort"))

        val forced = bodyFor("my-private-model", Reasoning.MODE_ON)
        assertTrue(forced.getBoolean("enable_thinking"))
    }
}
