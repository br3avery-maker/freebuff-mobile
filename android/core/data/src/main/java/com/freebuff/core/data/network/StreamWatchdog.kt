package com.freebuff.core.data.network

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope

/** 流式空闲看门狗默认阈值:连续 120s 没有任何事件到达则终止流。 */
const val DEFAULT_STREAM_IDLE_TIMEOUT_MS = 120_000L

/**
 * 流式空闲看门狗:连续 [idleTimeoutMs] 没有任何事件到达时,以 [ApiError.StreamIdle] 终止流。
 *
 * 背景:SSE 走 OkHttp 阻塞读,readTimeout 只在单次 socket read 上生效 —— 连接存活但
 * 服务端迟迟不吐数据(或中继挂起)时,消费方会无限等待「正在生成」。此算子把「无新事件」
 * 作为超时判据:每收到一个事件就重置计时;活动中的流不会被误杀,空闲超过阈值才触发。
 *
 * 实现:上游事件经无界 Channel 转发,消费循环用 withTimeoutOrNull 等待下一个事件。
 * 超时触发后取消转发协程 —— 经 callbackFlow 的 awaitClose 级联取消底层 HTTP 请求;
 * 上游正常完成立即结束(不额外等待);上游异常经 Channel 关闭原因透传给下游。
 *
 * [idleTimeoutMs] <= 0 时原样返回(禁用),供虚拟时钟单测关闭看门狗。
 */
fun <T> Flow<T>.idleWatchdog(idleTimeoutMs: Long = DEFAULT_STREAM_IDLE_TIMEOUT_MS): Flow<T> {
    if (idleTimeoutMs <= 0) return this
    return flow {
        coroutineScope {
            val events = Channel<T>(Channel.UNLIMITED)
            val pumper = launch {
                var failure: Throwable? = null
                try {
                    collect { events.send(it) }
                } catch (t: Throwable) {
                    // 不在子协程里再抛(会让作用域整体取消):经 Channel 关闭原因交给消费循环透传
                    failure = t
                } finally {
                    events.close(failure)
                }
            }
            try {
                while (true) {
                    val r = withTimeoutOrNull(idleTimeoutMs) { events.receiveCatching() }
                    when {
                        r == null -> throw ApiError.StreamIdle(idleTimeoutMs)
                        r.isSuccess -> emit(r.getOrThrow())
                        // 关闭且带原因(上游异常/取消)→ 原样透传;正常关闭(原因 null)→ 流结束
                        r.exceptionOrNull() != null -> throw r.exceptionOrNull()!!
                        else -> break
                    }
                }
            } finally {
                pumper.cancel()
            }
        }
    }
}
