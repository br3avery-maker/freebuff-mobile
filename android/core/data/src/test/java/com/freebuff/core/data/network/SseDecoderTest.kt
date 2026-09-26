package com.freebuff.core.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SSE 帧解码:跨行 data、注释/心跳、CRLF、NDJSON、末尾未收尾事件。 */
class SseDecoderTest {

    private fun feedAll(d: SseDecoder, lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        lines.forEach { out += d.feed(it) }
        out += d.flush()
        return out
    }

    @Test
    fun `单行事件按空行分隔`() {
        val d = SseDecoder()
        assertEquals(
            listOf("""{"a":1}""", """{"b":2}"""),
            feedAll(d, listOf("""data: {"a":1}""", "", """data: {"b":2}""", "")),
        )
    }

    @Test
    fun `跨行 data 拼接成一个事件`() {
        // 工具参数很长时网关会把一个 JSON 拆到多条 data 行(openai-python 是「不到空行不产出事件」)
        val d = SseDecoder()
        val out = feedAll(
            d,
            listOf(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":",
                "data: {\"arguments\":\"{\\\"a\\\":1}\"}}]}}]}",
                "",
            ),
        )
        assertEquals(1, out.size)
        assertTrue(out[0].startsWith("""{"choices"""))
        assertTrue(out[0].contains("\\\"a\\\"") || out[0].contains("\"a\""))
        // 拼接结果必须是合法 JSON
        org.json.JSONObject(out[0])
    }

    @Test
    fun `data 后只去掉一个空格`() {
        val d = SseDecoder()
        assertEquals(listOf("  x"), feedAll(d, listOf("data:   x", "")))
    }

    @Test
    fun `注释与 event id retry 字段被忽略`() {
        val d = SseDecoder()
        val out = feedAll(
            d,
            listOf(": keep-alive", "event: message", "id: 7", "retry: 3000", """data: {"ok":1}""", ""),
        )
        assertEquals(listOf("""{"ok":1}"""), out)
    }

    @Test
    fun `CRLF 与多余空行容错`() {
        val d = SseDecoder()
        assertEquals(listOf("""{"ok":1}"""), feedAll(d, listOf("\r", """data: {"ok":1}""", "\r", "")))
    }

    @Test
    fun `末尾事件没有空行收尾也要产出`() {
        // pi#9047:漏了 flush 会把收尾事件丢掉,表现是「流完了但什么都没收到」
        val d = SseDecoder()
        d.feed("""data: {"last":true}""")
        assertEquals(listOf("""{"last":true}"""), d.flush())
    }

    @Test
    fun `NDJSON 直发不加前缀也当载荷`() {
        // Ollama 原生 / 部分网关直发 JSON 行(不带 data:)
        val d = SseDecoder()
        assertEquals(listOf("""{"message":{"content":"hi"}}"""), feedAll(d, listOf("""{"message":{"content":"hi"}}""")))
    }

    @Test
    fun `DONE 载荷照常产出由上层识别`() {
        val d = SseDecoder()
        assertEquals(listOf("[DONE]"), feedAll(d, listOf("data: [DONE]", "")))
    }
}
