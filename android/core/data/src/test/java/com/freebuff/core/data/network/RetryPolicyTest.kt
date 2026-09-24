package com.freebuff.core.data.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 重试策略:瞬时失败判定与指数退避。 */
class RetryPolicyTest {

    @Test
    fun `瞬时网络失败可重试`() {
        assertTrue(RetryPolicy.isRetryable(ApiError.Timeout()))
        assertTrue(RetryPolicy.isRetryable(ApiError.Unreachable()))
        assertTrue(RetryPolicy.isRetryable(ApiError.Dns()))
        assertTrue(RetryPolicy.isRetryable(ApiError.StreamIdle(120_000L)))
    }

    @Test
    fun `限流与服务端错误可重试 其他 HTTP 码不可`() {
        assertTrue(RetryPolicy.isRetryable(ApiError.Http(429)))
        assertTrue(RetryPolicy.isRetryable(ApiError.Http(500)))
        assertTrue(RetryPolicy.isRetryable(ApiError.Http(503)))
        assertFalse(RetryPolicy.isRetryable(ApiError.Http(401)))
        assertFalse(RetryPolicy.isRetryable(ApiError.Http(404)))
        assertFalse(RetryPolicy.isRetryable(ApiError.Http(422)))
    }

    @Test
    fun `确定性失败不可重试`() {
        assertFalse(RetryPolicy.isRetryable(ApiError.Parse("bad json")))
        assertFalse(RetryPolicy.isRetryable(ApiError.Tls()))
        assertFalse(RetryPolicy.isRetryable(ApiError.NotConfigured("网关地址")))
        assertFalse(RetryPolicy.isRetryable(IllegalStateException("bug")))
    }

    @Test
    fun `退避曲线为指数 2s 4s 封顶 10s`() {
        assertEquals(2_000L, RetryPolicy.backoffMs(1))
        assertEquals(4_000L, RetryPolicy.backoffMs(2))
        assertEquals(8_000L, RetryPolicy.backoffMs(3))
        assertEquals(10_000L, RetryPolicy.backoffMs(4))
        assertEquals(10_000L, RetryPolicy.backoffMs(9))
    }

    @Test
    fun `退避等待可被中止`() = runTest {
        // shouldAbort 立即为 true:不应等待任何退避时长
        val waited = RetryPolicy.waitBackoff(retry = 1, shouldAbort = { true })
        assertFalse(waited)
    }

    @Test
    fun `退避等待完整执行`() = runTest {
        val waited = RetryPolicy.waitBackoff(retry = 1, shouldAbort = { false })
        assertTrue(waited)
    }
}
