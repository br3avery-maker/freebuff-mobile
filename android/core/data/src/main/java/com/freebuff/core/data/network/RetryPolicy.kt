package com.freebuff.core.data.network

import kotlinx.coroutines.delay

/**
 * LLM 调用失败自动重试策略(指数退避):
 * - 瞬时性失败([isRetryable]:网络抖动/超时/限流/5xx)自动重试,指数退避 [backoffMs]
 * - 确定性失败(鉴权 401/路径 404/参数 422/响应解析/TLS)重试必然再败,直接抛出
 *
 * 纯策略无状态;轮次级状态(已试次数/是否已有输出)由调用方(agent 循环)持有。
 * 单测见 RetryPolicyTest。
 */
object RetryPolicy {

    /** 最多自动重试次数(不含首次):失败 1 次 + 重试 2 次,共 3 次尝试。 */
    const val MAX_RETRIES = 2

    /** 首次退避基数(ms);第 n 次重试等待 [backoffMs] * 2^(n-1)。 */
    const val BASE_BACKOFF_MS = 2_000L

    /**
     * 该失败是否值得自动重试。
     * - [ApiError.Timeout]/[ApiError.Unreachable]/[ApiError.Dns]:网络抖动,重试常能恢复
     * - [ApiError.StreamIdle]:流空闲超时(看门狗),端点偶发挂起,重试合理
     * - [ApiError.Http] 429/5xx:限流与服务端瞬时故障
     * - 401/403/404/422 与 4xx 其他:请求本身的问题,重试无意义
     * - [ApiError.Parse]/[ApiError.Tls]/[ApiError.NotConfigured]:确定性失败
     */
    fun isRetryable(e: Throwable): Boolean = when (e) {
        is ApiError -> when (e) {
            is ApiError.Timeout, is ApiError.Unreachable, is ApiError.Dns, is ApiError.StreamIdle -> true
            is ApiError.Http -> e.code == 429 || e.code in 500..599
            else -> false
        }
        // 未经分类的原始 IO 异常(裸 IOException 而非已归类的子类)视为瞬时故障
        else -> e is java.io.IOException
    }

    /** 第 [retry](从 1 起)次重试前的退避时长(ms):2s → 4s → 8s…,封顶 10s。 */
    fun backoffMs(retry: Int): Long =
        (BASE_BACKOFF_MS shl (retry - 1).coerceIn(0, 20)).coerceAtMost(10_000L)

    /**
     * 退避等待;期间调用方可通过 [shouldAbort] 中止(如用户点了停止)。
     * @return true = 等满退避时长;false = 被中止,调用方应放弃重试
     */
    suspend fun waitBackoff(retry: Int, shouldAbort: () -> Boolean): Boolean {
        var waited = 0L
        val total = backoffMs(retry)
        while (waited < total) {
            if (shouldAbort()) return false
            val slice = if (total - waited > 200) 200L else total - waited
            delay(slice)
            waited += slice
        }
        return !shouldAbort()
    }
}
