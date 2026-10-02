package com.freebuff.core.data.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** 统一错误分类与结果包装。 */
class ApiErrorTest {

    @Test
    fun `超时归类 Timeout`() {
        val e = SocketTimeoutException("timeout").toApiError()
        assertTrue(e is ApiError.Timeout)
        assertEquals("Connection timed out. Check your network and endpoint.", e.userMessage)
    }

    @Test
    fun `流式空闲归类 StreamIdle 并带秒数文案`() {
        val e = ApiError.StreamIdle(120_000L)
        assertEquals("Connection timed out: no streaming data for 120 seconds. Stopped automatically. Check the endpoint or try another model.", e.userMessage)
        assertEquals(120_000L, (e as ApiError.StreamIdle).idleMs)
    }

    @Test
    fun `DNS 失败归类 Dns`() {
        assertTrue(UnknownHostException("h").toApiError() is ApiError.Dns)
    }

    @Test
    fun `TLS 失败归类 Tls`() {
        assertTrue(SSLHandshakeException("bad cert").toApiError() is ApiError.Tls)
    }

    @Test
    fun `连接拒绝归类 Unreachable`() {
        assertTrue(ConnectException("refused").toApiError() is ApiError.Unreachable)
    }

    @Test
    fun `明文 HTTP 被拦截归类 CleartextBlocked 并提示改用 https`() {
        val raw = java.net.UnknownServiceException(
            "CLEARTEXT communication to 10.0.2.2 not permitted by network security policy",
        ).toApiError()
        assertTrue(raw is ApiError.CleartextBlocked)
        assertTrue(raw.userMessage.contains("https"))
        assertFalse(raw.userMessage.contains("CLEARTEXT"))
    }

    @Test
    fun `已分类错误原样透传`() {
        val src = ApiError.NotConfigured("Built-in gateway URL")
        val mapped = src.toApiError()
        assertEquals(src, mapped)
    }

    @Test
    fun `已知 HTTP 码优先于异常类型`() {
        val e = SocketTimeoutException("timeout").toApiError(429)
        assertTrue(e is ApiError.Http)
        assertEquals(429, (e as ApiError.Http).code)
        assertEquals("Too many requests (429): try again later", e.userMessage)
    }

    @Test
    fun `HTTP 码文案覆盖鉴权 路径 限流 服务端`() {
        assertEquals("Authentication failed (401): check the API key", httpErrorMessage(401))
        assertEquals("Endpoint not found (404): check the /chat/completions path", httpErrorMessage(404))
        assertEquals("Too many requests (429): try again later", httpErrorMessage(429))
        assertEquals("Server error (503): try again later", httpErrorMessage(503))
        assertTrue(httpErrorMessage(418).contains("418"))
    }

    @Test
    fun `更新源的 HTTP 码不报模型端点的说辞`() {
        // 守卫目标:版本更新的 404 曾经直接复用对话文案,提示用户去检查 /chat/completions 路径
        val msg = httpErrorMessage(404, HttpTarget.UpdateSource)
        assertTrue(msg.contains("Update source"))
        assertFalse(msg.contains("chat/completions"))
        assertEquals(msg, ApiError.Http(404, "body", HttpTarget.UpdateSource).userMessage)
        // 对话侧文案保持不变
        assertEquals("Endpoint not found (404): check the /chat/completions path", httpErrorMessage(404))
    }

    @Test
    fun `未配置文案在中英混排时补空格`() {
        assertEquals("GitHub OAuth client_id not configured", ApiError.NotConfigured("GitHub OAuth client_id").userMessage)
        assertEquals("Built-in gateway URL not configured", ApiError.NotConfigured("Built-in gateway URL").userMessage)
    }

    @Test
    fun `未知异常归 Unknown 并保留原因`() {
        val e = IllegalStateException("boom").toApiError()
        assertTrue(e is ApiError.Unknown)
        assertEquals("boom", e.userMessage)
    }

    @Test
    fun `无 message 的异常在文案里自证类型`() {
        // NetworkOnMainThreadException(阻塞请求忘了切线程)就是无 message 的:只写「请求失败」
        // 等于把排查线索丢掉 —— 兜底文案必须把异常类型带出来
        val e = IllegalStateException().toApiError()
        assertTrue(e is ApiError.Unknown)
        assertEquals("Request failed (IllegalStateException)", e.userMessage)
    }

    @Test
    fun `mapChatError 等价于分类文案`() {
        assertEquals("Could not resolve the host. Check the endpoint URL.", mapChatError(UnknownHostException("x")))
        assertEquals("Authentication failed (401): check the API key", mapChatError(RuntimeException("any"), 401))
    }

    @Test
    fun `apiCall 成功包装 Ok 失败包装 Err`() = runTest {
        val ok = apiCall { 42 }
        assertTrue(ok is ApiResult.Ok)
        assertEquals(42, (ok as ApiResult.Ok).data)

        val err = apiCall<Int> { throw ApiError.Http(500) }
        assertTrue(err is ApiResult.Err)
        assertEquals(500, ((err as ApiResult.Err).error as ApiError.Http).code)
    }

    @Test
    fun `apiCallIo 在同一 IO 调度器上执行并归一化异常`() = runTest {
        val ok = apiCallIo { 7 }
        assertEquals(7, (ok as ApiResult.Ok).data)

        val err = apiCallIo<Int> { throw ConnectException("refused") }
        assertTrue((err as ApiResult.Err).error is ApiError.Unreachable)
    }

    @Test
    fun `ApiResult 辅助函数`() {
        val ok: ApiResult<String> = ApiResult.Ok("data")
        assertEquals("data", ok.getOrNull())
        assertTrue(ok.isOk)
        assertNull(ok.errorOrNull())
        assertNull(ok.errorMessageOrNull())

        val err: ApiResult<String> = ApiResult.Err(ApiError.NotConfigured("Update source URL"))
        assertNull(err.getOrNull())
        assertTrue(!err.isOk)
        assertEquals("Update source URL not configured", err.errorMessageOrNull())

        assertEquals("fallback", err.fold({ it }, { "fallback" }))
    }
}
