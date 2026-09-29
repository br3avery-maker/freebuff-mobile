package com.freebuff.core.data.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 静默重试:只重试瞬时故障,配置类错误立刻返回(重试只是白等)。 */
class SilentRetryTest {

    @Test
    fun `瞬时失败重试后成功`() = runTest {
        var calls = 0
        val r = silentRetry(attempts = 3, baseDelayMs = 1L) {
            calls++
            if (calls < 3) ApiResult.Err(ApiError.Timeout()) else ApiResult.Ok("ok")
        }
        assertTrue(r is ApiResult.Ok)
        assertEquals("ok", (r as ApiResult.Ok).data)
        assertEquals(3, calls)
    }

    @Test
    fun `配置类错误不重试`() = runTest {
        var calls = 0
        val r = silentRetry(attempts = 3, baseDelayMs = 1L) {
            calls++
            ApiResult.Err(ApiError.NotConfigured("更新源地址"))
        }
        assertTrue(r is ApiResult.Err)
        assertEquals(1, calls)
    }

    @Test
    fun `404 不重试而 5xx 重试`() = runTest {
        var notFound = 0
        silentRetry(attempts = 3, baseDelayMs = 1L) {
            notFound++
            ApiResult.Err(ApiError.Http(404))
        }
        assertEquals(1, notFound)

        var serverError = 0
        val r = silentRetry(attempts = 3, baseDelayMs = 1L) {
            serverError++
            ApiResult.Err(ApiError.Http(503))
        }
        assertEquals(3, serverError)
        assertEquals(503, ((r as ApiResult.Err).error as ApiError.Http).code)
    }

    @Test
    fun `全部失败时返回最后一次的错误`() = runTest {
        var calls = 0
        val r = silentRetry(attempts = 4, baseDelayMs = 1L) {
            calls++
            ApiResult.Err(ApiError.Dns())
        }
        assertEquals(4, calls)
        assertTrue((r as ApiResult.Err).error is ApiError.Dns)
    }

    @Test
    fun `明文被拦 TLS 与指纹不符都不重试`() = runTest {
        val fatal: List<ApiError> = listOf(
            ApiError.CleartextBlocked(),
            ApiError.Tls(),
            ApiError.ChecksumFailed("a".repeat(64), "b".repeat(64)),
        )
        for (err in fatal) {
            var calls = 0
            silentRetry(attempts = 3, baseDelayMs = 1L) {
                calls++
                ApiResult.Err(err)
            }
            assertEquals(err.toString(), 1, calls)
        }
    }

    @Test
    fun `attempts 为 1 时等同单次调用`() = runTest {
        var calls = 0
        val r = silentRetry(attempts = 1, baseDelayMs = 1L) {
            calls++
            ApiResult.Err(ApiError.Timeout())
        }
        assertEquals(1, calls)
        assertTrue(r is ApiResult.Err)
    }
}
