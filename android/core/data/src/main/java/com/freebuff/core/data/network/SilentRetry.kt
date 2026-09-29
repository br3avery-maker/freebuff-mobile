package com.freebuff.core.data.network

import kotlinx.coroutines.delay

/**
 * 静默重试:更新检查 / 下载这类「用户点了就等着」的调用,瞬时失败先自己重试,
 * 不留现场也不打断用户 —— 只有彻底失败才把可读错误交给界面。
 *
 * 为什么放在数据层而不是界面层:重试次数与退避属于传输层策略,界面只管展示最终结果;
 * 放这里还能直接对着 MockWebServer 断言(见 `SilentRetryTest`、`UpdateDownloaderTest`)。
 *
 * 什么会重试由 [ApiError.isTransient] 决定 —— 配置类错误(未配置 / 明文被拦 / 鉴权失败)
 * 重试只是白等,一律立刻返回。
 *
 * @param attempts 总尝试次数(含第一次),至少为 1
 * @param baseDelayMs 退避基数:第 n 次失败后等 `baseDelayMs * n`(线性退避,好预测、够用)
 * @param block 真正干活的那次调用;内部的 [kotlinx.coroutines.CancellationException]
 *              按结构化并发语义原样抛出,不会被吞成一次「失败重试」
 */
suspend fun <T> silentRetry(
    attempts: Int = 3,
    baseDelayMs: Long = 400L,
    block: suspend () -> ApiResult<T>,
): ApiResult<T> {
    require(attempts >= 1) { "attempts 至少为 1" }
    var last: ApiResult<T> = ApiResult.Err(ApiError.Unknown())
    for (i in 1..attempts) {
        val result = block()
        if (result is ApiResult.Ok) return result
        last = result
        if (i == attempts || !(result as ApiResult.Err).error.isTransient()) return result
        delay(baseDelayMs * i)
    }
    return last
}
