package com.freebuff.core.data.network

import com.freebuff.core.model.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考(思维链)增量的解析:字段名与形态各家不一,都要能落成 [AgentEvent.Reasoning]。
 * 覆盖:`reasoning_content`(事实标准)、`reasoning`、`thinking`、对象/数组形态、原生事件帧。
 */
class ReasoningStreamTest {

    private fun reasoningOf(vararg lines: String): String {
        val p = AgentEventParser()
        return lines.flatMap { p.feed(it) }
            .filterIsInstance<AgentEvent.Reasoning>()
            .joinToString("") { it.chunk }
    }

    @Test
    fun `reasoning_content 增量逐条发射且不进正文`() {
        val p = AgentEventParser()
        val evs = p.feed("""{"choices":[{"delta":{"reasoning_content":"先看","content":""}}]}""")
        assertEquals(1, evs.size)
        assertTrue(evs.single() is AgentEvent.Reasoning)
        assertEquals("先看", (evs.single() as AgentEvent.Reasoning).chunk)
    }

    @Test
    fun `同一帧里思考与正文都到齐时按顺序发射`() {
        val p = AgentEventParser()
        val evs = p.feed("""{"choices":[{"delta":{"reasoning_content":"想","content":"答"}}]}""")
        assertEquals(2, evs.size)
        assertEquals("想", (evs[0] as AgentEvent.Reasoning).chunk)
        assertEquals("答", (evs[1] as AgentEvent.Text).chunk)
    }

    @Test
    fun `兼容 reasoning 与 thinking 别名`() {
        assertEquals("A", reasoningOf("""{"choices":[{"delta":{"reasoning":"A"}}]}"""))
        assertEquals("B", reasoningOf("""{"choices":[{"delta":{"thinking":"B"}}]}"""))
    }

    @Test
    fun `个别网关把 reasoning_content 序列化成对象或数组也要还原成文本`() {
        assertEquals(
            "对象里的思考",
            reasoningOf("""{"choices":[{"delta":{"reasoning_content":{"text":"对象里的思考"}}}]}"""),
        )
        assertEquals(
            "片段一二",
            reasoningOf(
                """{"choices":[{"delta":{"reasoning_content":[{"text":"片段一"},{"text":"二"}]}}]}""",
            ),
        )
    }

    @Test
    fun `非流式响应里的思考同样解析`() {
        assertEquals(
            "整段思考",
            reasoningOf("""{"choices":[{"message":{"reasoning_content":"整段思考","content":"答案"}}]}"""),
        )
    }

    @Test
    fun `原生事件帧的思考类型映射为 Reasoning`() {
        assertEquals("深度思考中", reasoningOf("""{"type":"reasoning","text":"深度思考中"}"""))
        assertEquals("thinking 帧", reasoningOf("""{"type":"thinking","chunk":"thinking 帧"}"""))
    }

    @Test
    fun `没有思考字段时不产生事件`() {
        assertEquals("", reasoningOf("""{"choices":[{"delta":{"content":"只有正文"}}]}"""))
        assertEquals("", reasoningOf("""{"choices":[{"delta":{}}]}"""))
    }
}
