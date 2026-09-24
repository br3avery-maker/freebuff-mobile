package com.freebuff.core.data.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** 流式空闲看门狗:超时终止、事件重置计时、上游完成与异常透传。 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamWatchdogTest {

    // 端到端(真实时钟):chatStream 默认开启看门狗,服务端静默后必须超时终止
    private lateinit var server: MockWebServer
    private val repo = com.freebuff.core.data.repository.ChatRepository()

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
    fun `空闲超过阈值以 StreamIdle 终止`() = runTest {
        val fs = flow {
            emit(1)
            delay(10_000) // 之后不再发任何事件
        }.idleWatchdog(idleTimeoutMs = 1_000)

        val events = mutableListOf<Int>()
        try {
            fs.toList(events)
        } catch (e: ApiError.StreamIdle) {
            assertEquals(1_000L, e.idleMs)
            assertEquals("已收到的事件保留,超时仅终止流", listOf(1), events)
            return@runTest
        }
        throw AssertionError("应抛出 ApiError.StreamIdle")
    }

    @Test
    fun `事件持续到达则不触发超时`() = runTest {
        // 每 100ms 发一个事件,共 20 个:相邻间隔远小于 500ms 阈值,不应触发
        val fs = flow {
            repeat(20) {
                delay(100)
                emit(it)
            }
        }.idleWatchdog(idleTimeoutMs = 500)

        assertEquals((0 until 20).toList(), fs.toList())
    }

    @Test
    fun `上游正常完成立即结束不额外等待`() = runTest {
        val fs = flow {
            emit("a")
            emit("b")
        }.idleWatchdog(idleTimeoutMs = 60_000)

        assertEquals(listOf("a", "b"), fs.toList())
    }

    @Test
    fun `上游异常原样透传`() = runTest {
        val fs = flow<Int> {
            emit(1)
            throw IllegalStateException("boom")
        }.idleWatchdog(idleTimeoutMs = 60_000)

        try {
            fs.toList()
            throw AssertionError("应抛出上游异常")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }

    @Test
    fun `触发超时时上游协程被取消`() = runTest {
        var ranToCompletion = false
        val fs = flow {
            emit(1)
            delay(60_000) // 上游挂在长延迟上;若未被取消,标志位会被置 true
            ranToCompletion = true
        }.idleWatchdog(idleTimeoutMs = 1_000)

        var caught: Throwable? = null
        val job = launch {
            try {
                fs.toList()
            } catch (t: Throwable) {
                caught = t
            }
        }
        advanceTimeBy(2_000)
        runCurrent()
        job.cancel()
        assertTrue("超时应以 StreamIdle 终止", caught is ApiError.StreamIdle)
        assertTrue("超时后上游应已被取消,不会跑完", !ranToCompletion)
    }

    @Test
    fun `下游取消立即传播到上游`() = runTest {
        var ranToCompletion = false
        val fs = flow {
            emit(1)
            delay(60_000)
            ranToCompletion = true
        }.idleWatchdog(idleTimeoutMs = 60_000)

        val collector = async { fs.toList() }
        runCurrent()
        advanceTimeBy(10)
        runCurrent()
        collector.cancel()
        runCurrent()
        assertTrue(!ranToCompletion)
    }

    /** 真实时钟下收集 chatStream;虚拟时钟会让 withTimeoutOrNull 立即超时,故不能用 runTest。 */
    private fun chatStreamRealtime(idleTimeoutMs: Long) =
        runBlocking {
            repo.chatStream(
                endpoint = server.url("/v1").toString(),
                model = "m",
                apiKey = "k",
                headers = emptyMap(),
                skipTLS = false,
                history = listOf(ChatMessage.text("user", "hi")),
                idleTimeoutMs = idleTimeoutMs,
            ).toList()
        }

    @Test
    fun `端到端 - 服务端不响应 看门狗超时终止`() {
        // NO_RESPONSE:接受连接但永不回响应 —— 真实世界里「挂死」的形态
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val t0 = System.currentTimeMillis()
        try {
            chatStreamRealtime(idleTimeoutMs = 500)
            throw AssertionError("应抛出 StreamIdle")
        } catch (e: ApiError.StreamIdle) {
            assertEquals(500L, e.idleMs)
            val elapsed = System.currentTimeMillis() - t0
            assertTrue("应在阈值附近触发,实际 ${elapsed}ms", elapsed in 400..5_000)
        }
    }

    // 注:「发完部分 body 后中途静默」的形态 MockWebServer 无法构造
    // (KEEP_OPEN 不挂有界定界的 body,chunked 终止块总会写),挂死场景由 NO_RESPONSE 覆盖。

    @Test
    fun `端到端 - 正常完成不触发看门狗`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        val texts = chatStreamRealtime(idleTimeoutMs = 5_000)
            .filterIsInstance<com.freebuff.core.model.AgentEvent.Text>()
            .map { it.chunk }
        assertEquals(listOf("你", "好"), texts)
    }
}
