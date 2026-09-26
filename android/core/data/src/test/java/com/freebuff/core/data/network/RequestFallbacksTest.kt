package com.freebuff.core.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 请求侧降级识别:只认「错误文本指向该字段」的情形,别的 400 必须照常报错。 */
class RequestFallbacksTest {

    @Test
    fun `端点不支持工具定义时识别出来`() {
        assertTrue(
            RequestFallbacks.toolsRejected(
                400,
                """{"error":{"message":"'tools' is not supported by this model","type":"invalid_request_error"}}""",
            ),
        )
        assertTrue(RequestFallbacks.toolsRejected(400, """{"error":"does not support function calling"}"""))
        assertTrue(RequestFallbacks.toolsRejected(400, "不支持 tools 参数"))
        assertTrue(RequestFallbacks.toolsRejected(422, """{"detail":"Unrecognized field: tool_choice"}"""))
        assertTrue(RequestFallbacks.toolsRejected(500, """{"error":{"message":"Invalid parameter: functions"}}"""))
    }

    @Test
    fun `额度不足鉴权失败等真问题不触发降级`() {
        assertFalse(RequestFallbacks.toolsRejected(400, """{"error":{"message":"Insufficient quota"}}"""))
        assertFalse(RequestFallbacks.toolsRejected(401, """{"error":{"message":"invalid api key"}}"""))
        assertFalse(RequestFallbacks.toolsRejected(429, """{"error":{"message":"rate limit exceeded"}}"""))
        assertFalse(RequestFallbacks.toolsRejected(400, """{"error":{"message":"max_tokens must be >= 1"}}"""))
        assertFalse(RequestFallbacks.toolsRejected(400, ""))
    }

    @Test
    fun `端点不认识思考参数时识别出来`() {
        assertTrue(
            RequestFallbacks.reasoningRejected(
                400,
                """{"error":{"message":"Unknown field: enable_thinking"}}""",
            ),
        )
        assertTrue(RequestFallbacks.reasoningRejected(400, """{"error":"unexpected parameter reasoning_effort"}"""))
        assertTrue(RequestFallbacks.reasoningRejected(400, """{"error":{"message":"thinking is not supported"}}"""))
        assertTrue(RequestFallbacks.reasoningRejected(400, "不支持 思考 参数"))
    }

    @Test
    fun `思考参数与工具字段互不误判`() {
        // 工具被拒不该被当成思考参数问题,反之亦然(否则会去掉错误的字段重发)
        assertFalse(RequestFallbacks.reasoningRejected(400, """{"error":{"message":"'tools' is not supported"}}"""))
        assertFalse(RequestFallbacks.toolsRejected(400, """{"error":{"message":"Unknown field: enable_thinking"}}"""))
    }
}
