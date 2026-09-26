package com.freebuff.core.data.network

import com.freebuff.core.model.AgentEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 各家 OpenAI 兼容实现的解析变体(每条都对应一次真实踩坑 / 上游 issue):
 * 无 index 的并行调用、空参数、结构化参数、遗留 function_call、HTTP 200 夹错误帧、数组部件内容、参数瑕疵。
 */
class ProviderVariantsTest {

    private fun calls(parser: AgentEventParser): List<com.freebuff.core.model.ToolCallReq> =
        parser.drainCalls()

    @Test
    fun `Gemini 式无 index 的并行调用按 id 分槽`() {
        // Gemini 的 OpenAI 兼容层 delta.tool_calls 不带 index;按下标归并会把两个调用合成一个
        val p = AgentEventParser()
        p.feed("""{"choices":[{"delta":{"tool_calls":[{"id":"call_a","function":{"name":"current_time"}}]}}]}""")
        p.feed("""{"choices":[{"delta":{"tool_calls":[{"id":"call_b","function":{"name":"calculator","arguments":"{\"expression\":\"1+1\"}"}}]}}]}""")
        p.feed("""{"choices":[{"delta":{"tool_calls":[{"id":"call_a","function":{"arguments":"{}"}}]}}]}""")
        p.feed("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""")
        val out = calls(p)
        assertEquals(2, out.size)
        assertEquals("current_time", out[0].name)
        assertEquals("{}", out[0].argsJson)
        assertEquals("calculator", out[1].name)
        assertEquals("""{"expression":"1+1"}""", out[1].argsJson)
        assertEquals("call_a", out[0].callId)
        assertEquals("call_b", out[1].callId)
    }

    @Test
    fun `空参数视为空对象可执行`() {
        // Anthropic 经兼容层对无参工具发 "arguments": ""(vercel/ai#6687)
        val p = AgentEventParser()
        p.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"toolu_1","function":{"name":"current_time","arguments":""}}]}}]}""")
        p.feed("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""")
        val out = calls(p)
        assertEquals(1, out.size)
        assertEquals("{}", out[0].argsJson)
    }

    @Test
    fun `参数以对象下发时原样序列化`() {
        val p = AgentEventParser()
        p.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"calculator","arguments":{"expression":"2*3"}}}]}}]}""")
        p.feed("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""")
        assertEquals("""{"expression":"2*3"}""", calls(p).single().argsJson)
    }

    @Test
    fun `名称整段重发不会拼成重复名`() {
        // 部分网关每个分片都带完整 name:盲目追加会得到 web_searchweb_search
        val p = AgentEventParser()
        p.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"web_search"}}]}}]}""")
        p.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"web_search","arguments":"{}"}}]}}]}""")
        p.feed("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""")
        assertEquals("web_search", calls(p).single().name)
    }

    @Test
    fun `参数被拆成很多分片也能拼回合法 JSON`() {
        val p = AgentEventParser()
        p.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"calculator","arguments":"{\"expr"}}]}}]}""")
        p.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ession\":\"(1+"}}]}}]}""")
        p.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"2)*3\"}"}}]}}]}""")
        p.feed("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""")
        assertEquals("""{"expression":"(1+2)*3"}""", calls(p).single().argsJson)
    }

    @Test
    fun `遗留 function_call 协议可用`() {
        val p = AgentEventParser()
        p.feed("""{"choices":[{"delta":{"function_call":{"name":"calculator","arguments":"{\"expression\":\"1\"}"}}}]}""")
        p.feed("""{"choices":[{"delta":{},"finish_reason":"function_call"}]}""")
        val out = calls(p)
        assertEquals("calculator", out.single().name)
        assertEquals("""{"expression":"1"}""", out.single().argsJson)
    }

    @Test
    fun `HTTP 200 里的错误帧转成 Failure 而不是静默`() {
        // 不识别会「流在跑但什么都不吐」,一直挂到空闲看门狗
        val p = AgentEventParser()
        val ev = p.feed("""{"error":{"message":"invalid api key","type":"auth_error"}}""")
        assertEquals(1, ev.size)
        assertEquals("invalid api key", (ev.single() as AgentEvent.Failure).message)
    }

    @Test
    fun `数组部件形式的正文与思考`() {
        val p = AgentEventParser()
        val evs = p.feed(
            """{"choices":[{"delta":{"reasoning_content":[{"type":"text","text":"想一步"}],"content":[{"type":"text","text":"回答"}]}}]}""",
        )
        assertEquals(2, evs.size)
        assertEquals("想一步", (evs[0] as AgentEvent.Reasoning).chunk)
        assertEquals("回答", (evs[1] as AgentEvent.Text).chunk)
    }

    @Test
    fun `思考字段名差异都能识别 reasoning 与 reasoning_text`() {
        val p = AgentEventParser()
        assertEquals(
            "A",
            (p.feed("""{"choices":[{"delta":{"reasoning":"A"}}]}""").single() as AgentEvent.Reasoning).chunk,
        )
        assertEquals(
            "B",
            (p.feed("""{"choices":[{"delta":{"reasoning_text":"B"}}]}""").single() as AgentEvent.Reasoning).chunk,
        )
    }

    @Test
    fun `带栅栏与尾逗号的参数自动修复`() {
        val p = AgentEventParser()
        p.feed(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"calculator","arguments":"```json\n{\"expression\": \"1+1\",}\n```"}}]}}]}""",
        )
        val out = calls(p)
        assertEquals("calculator", out.single().name)
        assertEquals("1+1", JSONObject(out.single().argsJson).optString("expression"))
    }

    @Test
    fun `修不好的参数原样交给执行器`() {
        val p = AgentEventParser()
        p.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"calculator","arguments":"表达式是 1+1"}}]}}]}""")
        val out = calls(p)
        assertEquals("表达式是 1+1", out.single().argsJson)
    }

    @Test
    fun `心跳帧与 usage 帧不影响解析`() {
        val p = AgentEventParser()
        assertTrue(p.feed("""{"choices":[]}""").isEmpty())
        assertTrue(p.feed("""{"usage":{"total_tokens":5}}""").isEmpty())
        assertTrue(p.feed("").isEmpty())
        assertTrue(p.feed("data: [DONE]").isEmpty())
    }
}
