package com.freebuff.core.data.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        assertEquals("连接超时,请检查网络或端点可达性", e.userMessage)
    }

    @Test
    fun `流式空闲归类 StreamIdle 并带秒数文案`() {
        val e = ApiError.StreamIdle(120_000L)
        assertEquals("连接超时:超过 120 秒没有收到任何流式数据,已自动停止。请检查端点状态或换个模型再试", e.userMessage)
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
    fun `已分类错误原样透传`() {
        val src = ApiError.NotConfigured("官方网关地址")
        val mapped = src.toApiError()
        assertEquals(src, mapped)
    }

    @Test
    fun `已知 HTTP 码优先于异常类型`() {
        val e = SocketTimeoutException("timeout").toApiError(429)
        assertTrue(e is ApiError.Http)
        assertEquals(429, (e as ApiError.Http).code)
        assertEquals("请求过于频繁(429):请稍后再试", e.userMessage)
    }

    @Test
    fun `HTTP 码文案覆盖鉴权 路径 限流 服务端`() {
        assertEquals("鉴权失败(401):请检查 API Key", httpErrorMessage(401))
        assertEquals("端点不存在(404):请确认 /chat/completions 路径", httpErrorMessage(404))
        assertEquals("请求过于频繁(429):请稍后再试", httpErrorMessage(429))
        assertEquals("服务端错误(503):请稍后再试", httpErrorMessage(503))
        assertTrue(httpErrorMessage(418).contains("418"))
    }

    @Test
    fun `未知异常归 Unknown 并保留原因`() {
        val e = IllegalStateException("boom").toApiError()
        assertTrue(e is ApiError.Unknown)
        assertEquals("boom", e.userMessage)
    }

    @Test
    fun `mapChatError 等价于分类文案`() {
        assertEquals("无法解析主机,请检查端点地址", mapChatError(UnknownHostException("x")))
        assertEquals("鉴权失败(401):请检查 API Key", mapChatError(RuntimeException("any"), 401))
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

        val err: ApiResult<String> = ApiResult.Err(ApiError.NotConfigured("更新源地址"))
        assertNull(err.getOrNull())
        assertTrue(!err.isOk)
        assertEquals("更新源地址未配置", err.errorMessageOrNull())

        assertEquals("fallback", err.fold({ it }, { "fallback" }))
    }
}
