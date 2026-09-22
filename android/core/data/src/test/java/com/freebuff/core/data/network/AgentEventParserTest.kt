package com.freebuff.core.data.network

import com.freebuff.core.model.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Agent 事件流解析器:OpenAI 增量参数累积、Freebuff 原生事件帧、容错。 */
class AgentEventParserTest {

    /* ---------------- OpenAI 兼容流 ---------------- */

    @Test
    fun `文本增量逐条发射`() {
        val p = AgentEventParser()
        val ev1 = p.feed("""{"choices":[{"delta":{"content":"你"}}]}""")
        val ev2 = p.feed("""{"choices":[{"delta":{"content":"好"}}]}""")
        assertTrue(ev1.single() is AgentEvent.Text)
        assertEquals("你", (ev1.single() as AgentEvent.Text).chunk)
        assertEquals("好", (ev2.single() as AgentEvent.Text).chunk)
    }

    @Test
    fun `tool_calls 参数增量累积到 finish_reason 一次性落卡`() {
        val p = AgentEventParser()
        // 参数分三片到达
        p.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_files","arguments":"{\"pa"}}]}}]}""")
        p.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"a.t"}}]}}]}""")
        p.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"s\"}"}}]}}]}""")
        // finish_reason 落卡
        val evs = p.feed("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""")
        val call = evs.filterIsInstance<AgentEvent.ToolCall>().single()
        assertEquals("call_1", call.callId)
        assertEquals("read_files", call.tool)
        assertEquals("a.ts", call.input)
    }

    @Test
    fun `无 finish_reason 时 flush 兜底落卡`() {
        val p = AgentEventParser()
        p.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c9","function":{"name":"write_file","arguments":"{\"path\":\"x.kt\",\"content\":\"fun main()\"}"}}]}}]}""")
        val evs = p.flush()
        val call = evs.filterIsInstance<AgentEvent.ToolCall>().single()
        assertEquals("c9", call.callId)
        assertEquals("x.kt", call.input)
        // flush 幂等:再次 flush 不重复
        assertTrue(p.flush().isEmpty())
    }

    @Test
    fun `非流式 message 中完整 tool_calls 一次性解析`() {
        val p = AgentEventParser()
        val evs = p.feed(
            """{"choices":[{"message":{"content":"","tool_calls":[{"id":"t1","function":{"name":"run_terminal_command","arguments":"{\"command\":\"ls -la\"}"}}]}}]}""",
        )
        val call = evs.filterIsInstance<AgentEvent.ToolCall>().single()
        assertEquals("t1", call.callId)
        assertEquals("run_terminal_command", call.tool)
        assertEquals("ls -la", call.input)
    }

    @Test
    fun `命令摘要取 command 字段`() {
        val p = AgentEventParser()
        val evs = p.feed("""{"choices":[{"message":{"tool_calls":[{"id":"c","function":{"name":"run_terminal_command","arguments":"{\"command\":\"git status\"}"}}]}}]}""")
        assertEquals("git status", (evs.single() as AgentEvent.ToolCall).input)
    }

    /* ---------------- Freebuff 原生事件帧 ---------------- */

    @Test
    fun `原生 tool_call 与 tool_result 成对映射`() {
        val p = AgentEventParser()
        val call = p.feed("""{"type":"tool_call","toolCallId":"tc1","toolName":"code_search","input":{"pattern":"foo","cwd":"src"}}""")
            .filterIsInstance<AgentEvent.ToolCall>().single()
        assertEquals("tc1", call.callId)
        assertEquals("code_search", call.tool)
        assertEquals("foo", call.input)

        val done = p.feed("""{"type":"tool_result","toolCallId":"tc1","output":{"summary":"3 matches"},"isError":false}""")
            .filterIsInstance<AgentEvent.ToolResult>().single()
        assertEquals("tc1", done.callId)
        assertEquals("3 matches", done.output)
        assertTrue(!done.isError)
    }

    @Test
    fun `原生 error 事件映射为 Failure`() {
        val p = AgentEventParser()
        val ev = p.feed("""{"type":"error","message":"参数校验失败"}""").single()
        assertEquals("参数校验失败", (ev as AgentEvent.Failure).message)
    }

    @Test
    fun `子代理 chunk 按 agent 累积成卡片`() {
        val p = AgentEventParser()
        val e1 = p.feed("""{"type":"subagent-response-chunk","agentType":"thinker","agentId":"ag1","text":"分析"}""").single() as AgentEvent.ToolCall
        val e2 = p.feed("""{"type":"subagent-response-chunk","agentType":"thinker","agentId":"ag1","text":"中"}""").single() as AgentEvent.ToolCall
        assertEquals("子代理前缀", "subagent:thinker", e1.tool)
        assertEquals("流式累积", "分析中", e2.input)
        assertEquals("同一 callId 便于 App 端 upsert", e1.callId, e2.callId)
    }

    @Test
    fun `end_turn 事件映射`() {
        val p = AgentEventParser()
        assertTrue(p.feed("""{"type":"end_turn"}""").single() is AgentEvent.EndTurn)
    }

    /* ---------------- 容错 ---------------- */

    @Test
    fun `非法 JSON 与 DONE 与空行静默忽略`() {
        val p = AgentEventParser()
        assertTrue(p.feed("not json").isEmpty())
        assertTrue(p.feed("[DONE]").isEmpty())
        assertTrue(p.feed("   ").isEmpty())
        assertTrue(p.feed("""{"unknown":1}""").isEmpty())
    }

    @Test
    fun `乱序与混合流不崩溃且按到达顺序发射`() {
        val p = AgentEventParser()
        val evs = listOf(
            """{"type":"text","text":"开始"}""",
            """{"choices":[{"delta":{"content":"混合"}}]}""",
            """{"type":"tool_call","toolCallId":"x","toolName":"glob","input":{"pattern":"*.kt"}}""",
            """garbage line""",
            """{"type":"tool_result","toolCallId":"x","output":"2 files","isError":true}""",
        ).flatMap { p.feed(it) }
        assertEquals(4, evs.size)
        assertTrue(evs[0] is AgentEvent.Text)
        assertTrue(evs[1] is AgentEvent.Text)
        assertTrue(evs[2] is AgentEvent.ToolCall)
        val res = evs[3] as AgentEvent.ToolResult
        assertTrue(res.isError)
        assertEquals("2 files", res.output)
    }
}
