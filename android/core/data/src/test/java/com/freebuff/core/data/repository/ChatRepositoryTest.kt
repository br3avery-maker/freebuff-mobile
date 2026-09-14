package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ApiError
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** 真实 SSE 流式对话:MockWebServer 验证请求结构、增量解析与错误映射。 */
class ChatRepositoryTest {
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

    private fun stream(history: List<Pair<String, String>> = emptyList()) =
        repo.chatStream(
            endpoint = server.url("/v1").toString(),
            model = "gpt-x",
            apiKey = "secret",
            headers = emptyMap(),
            skipTLS = false,
            history = history,
        )

    @Test
    fun `流式返回增量文本片段`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        assertEquals(listOf("你", "好"), stream().toList())
    }

    @Test
    fun `请求包含正确路径鉴权头与 body`() = runTest {
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n"))
        repo.chatStream(
            endpoint = server.url("/v1").toString(),
            model = "gpt-x",
            apiKey = "secret",
            headers = mapOf("X-Custom" to "1"),
            skipTLS = false,
            history = listOf("user" to "hi"),
        ).toList()

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer secret", req.getHeader("Authorization"))
        assertEquals("1", req.getHeader("X-Custom"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"model\":\"gpt-x\""))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"content\":\"hi\""))
        assertTrue(body.contains("\"role\":\"user\""))
    }

    @Test
    fun `跳过注释空行与结束标记`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                ": keep-alive\n\n" +
                    "data: {\"choices\":[{\"text\":\"兼容\"}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        // 兼容结构 choices[0].text
        assertEquals(listOf("兼容"), stream().toList())
    }

    @Test
    fun `HTTP 401 抛出分类的 ApiError`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"unauthorized\"}"))
        try {
            stream().toList()
            fail("应抛出异常")
        } catch (e: ApiError.Http) {
            assertEquals(401, e.code)
            assertEquals("鉴权失败(401):请检查 API Key", e.userMessage)
            assertTrue("应保留响应片段,实际: ${e.body}", e.body.contains("unauthorized"))
        }
    }

    @Test
    fun `连接失败时以异常结束`() = runTest {
        server.shutdown()
        try {
            repo.chatStream(
                endpoint = "http://127.0.0.1:1/v1", // 必然连接失败
                model = "m",
                apiKey = "",
                headers = emptyMap(),
                skipTLS = false,
                history = emptyList(),
            ).toList()
            fail("应抛出异常")
        } catch (e: Exception) {
            // IOException(非 RuntimeException 子类)经 callbackFlow 抛出,以异常结束 flow
        }
    }
}
