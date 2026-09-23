package com.freebuff.core.data.network

import com.freebuff.core.model.AgentEvent
import com.freebuff.core.model.ToolCallReq
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 事件流解析器(有状态)。
 *
 * 输入一行 SSE `data:` 载荷,输出零个或多个 [AgentEvent]。按 agent-architecture.md §4 契约,
 * App 按到达顺序处理即可;解析器内部只做两类有状态处理:
 *
 * 1. OpenAI 兼容流的 `delta.tool_calls` 参数增量:arguments 分片按 index 累积,
 *    流式期间卡片 input 显示「(生成中)」,流结束后解析完整参数;
 * 2. Freebuff 原生事件(backend/data 下的事件帧):按 type 直接映射,子代理文本按
 *    agent + 消息 id 累积成卡片(流式期间逐次 upsert,替换输出内容)。
 *
 * 兼容性:纯 OpenAI 文本流、Freebuff 事件流、两者混合都能解析;未知结构静默忽略(不影响流)。
 */
class AgentEventParser {

    /** OpenAI 兼容流的工具参数累积:index → 已收到的 arguments 片段。 */
    private val pendingArgs = mutableMapOf<Int, StringBuilder>()

    /** OpenAI 兼容流的工具名累积(index → name,可能分片)。 */
    private val pendingNames = mutableMapOf<Int, StringBuilder>()

    /** OpenAI 兼容流的 callId 累积。 */
    private val pendingIds = mutableMapOf<Int, String>()

    /** 已知工具调用的稳定顺序号(callId → 序号),用于无 id 时的本地合成。 */
    private val callOrder = mutableMapOf<String, Int>()
    private var nextOrder = 0

    /** 子代理卡片累积:key = "$agent|$msgId" → 已收输出。 */
    private val subagentBuffers = mutableMapOf<String, StringBuilder>()
    private var subagentSeq = 0

    /** 已结束的 OpenAI 工具调用(index → callId),参数分片完成时发 ToolCall 一次。 */
    private val finishedCalls = mutableSetOf<Int>()

    /**
     * 解析一行 SSE data 载荷。
     * @return 解析出的事件,按到达顺序;未知/空结构返回空列表
     */
    fun feed(data: String): List<AgentEvent> {
        if (data.isBlank() || data == "[DONE]") return emptyList()
        val obj = try {
            JSONObject(data)
        } catch (e: Exception) {
            return emptyList()
        }
        return when {
            // Freebuff 原生事件帧:type 字段直接映射
            obj.has("type") -> parseTyped(obj)
            // OpenAI 兼容:choices[0].delta / message
            obj.has("choices") -> parseOpenAi(obj)
            else -> emptyList()
        }
    }

    /**
     * 流结束时调用:把参数累积中仍未落定的 OpenAI 工具调用落成卡片。
     * 兜底部分端点不发 finish_reason=tool_calls 的情况。
     */
    fun flush(): List<AgentEvent> = flushPendingCalls()

    /**
     * 流结束后取走本轮全部结构化工具调用(完整参数 JSON + callId),供 agent 循环执行与回传。
     * 取走后解析器内部状态清零,可安全复用于下一轮请求。
     * 与 [flush] 的卡片事件一一对应:flush 发卡片,drain 喂循环。
     */
    fun drainCalls(): List<ToolCallReq> {
        val out = mutableListOf<ToolCallReq>()
        for (idx in (pendingIds.keys + pendingNames.keys + pendingArgs.keys).toSortedSet()) {
            val name = pendingNames[idx]?.toString().orEmpty()
            if (name.isBlank()) continue
            out += ToolCallReq(
                callId = pendingIds[idx]?.takeIf { it.isNotBlank() } ?: ("call-" + idx + "-" + name),
                name = name,
                argsJson = pendingArgs[idx]?.toString().orEmpty(),
            )
        }
        pendingIds.clear()
        pendingNames.clear()
        pendingArgs.clear()
        return out
    }

    /* ---------------- Freebuff 原生事件帧 ---------------- */

    private fun parseTyped(obj: JSONObject): List<AgentEvent> {
        val type = obj.optString("type")
        return when (type) {
            "text", "text-chunk", "response-chunk" -> {
                val chunk = obj.optString("text", obj.optString("chunk"))
                if (chunk.isEmpty()) emptyList() else listOf(AgentEvent.Text(chunk))
            }

            "tool_call" -> {
                val callId = obj.optString("toolCallId").ifBlank { obj.optString("callId") }
                val tool = obj.optString("toolName").ifBlank { obj.optString("tool") }
                if (tool.isEmpty()) emptyList()
                else listOf(
                    AgentEvent.ToolCall(
                        callId = callId.ifBlank { syntheticId(tool) },
                        tool = tool,
                        input = summarizeInput(obj.optJSONObject("input") ?: obj.optJSONObject("args")),
                    ),
                )
            }

            "tool_result" -> {
                val callId = obj.optString("toolCallId").ifBlank { obj.optString("callId") }
                if (callId.isEmpty()) emptyList()
                else listOf(
                    AgentEvent.ToolResult(
                        callId = callId,
                        output = summarizeOutput(obj.opt("output") ?: obj.opt("result")),
                        isError = obj.optBoolean("isError", obj.optBoolean("error", false)),
                    ),
                )
            }

            "error" -> {
                val msg = obj.optString("message", obj.optString("error"))
                if (msg.isEmpty()) emptyList() else listOf(AgentEvent.Failure(msg))
            }

            "subagent-response-chunk", "subagent-chunk" -> {
                val agent = obj.optString("agentType", obj.optString("agent", "subagent"))
                val msgId = obj.optString("agentId", obj.optString("messageId", agent))
                val chunk = obj.optString("text", obj.optString("chunk"))
                if (chunk.isEmpty()) return emptyList()
                val key = "$agent|$msgId"
                val buf = subagentBuffers.getOrPut(key) { StringBuilder() }
                buf.append(chunk)
                val callId = "subagent-$key"
                if (callId !in callOrder) callOrder[callId] = nextOrder++
                listOf(AgentEvent.ToolCall(callId, ToolCardMarker.SUBAGENT + agent, buf.toString()))
            }

            "subagent-response", "subagent-result" -> {
                val agent = obj.optString("agentType", obj.optString("agent", "subagent"))
                val msgId = obj.optString("agentId", obj.optString("messageId", agent))
                val callId = "subagent-$agent|$msgId"
                val full = obj.optString("text", obj.optString("output"))
                listOf(AgentEvent.ToolResult(callId, full.ifBlank { subagentBuffers[callId]?.toString().orEmpty() }))
            }

            "end_turn", "turn-end", "done" -> listOf(AgentEvent.EndTurn)

            else -> emptyList()
        }
    }

    /* ---------------- OpenAI 兼容增量 ---------------- */

    private fun parseOpenAi(obj: JSONObject): List<AgentEvent> {
        val choices = obj.optJSONArray("choices") ?: return emptyList()
        val c = choices.optJSONObject(0) ?: return emptyList()
        val events = mutableListOf<AgentEvent>()

        // 文本增量(delta.content / text / message.content)
        val delta = c.optJSONObject("delta")
        val text = when {
            delta != null && delta.has("content") && !delta.isNull("content") -> delta.optString("content")
            c.has("text") && !c.isNull("text") -> c.optString("text")
            c.has("message") -> {
                val m = c.optJSONObject("message")
                if (m != null && m.has("content") && !m.isNull("content")) m.optString("content") else ""
            }
            else -> ""
        }
        if (text.isNotEmpty()) events += AgentEvent.Text(text)

        // 工具调用增量:delta.tool_calls[] 的 arguments 分片累积
        if (delta != null) {
            val calls = delta.optJSONArray("tool_calls")
            if (calls != null) {
                for (i in 0 until calls.length()) {
                    val tc = calls.optJSONObject(i) ?: continue
                    val idx = tc.optInt("index", i)
                    tc.optString("id").takeIf { it.isNotBlank() }?.let { pendingIds[idx] = it }
                    tc.optString("name").takeIf { it.isNotBlank() }?.let { pendingNames.getOrPut(idx) { StringBuilder() }.append(it) }
                    tc.optString("function_name").takeIf { it.isNotBlank() }?.let { pendingNames.getOrPut(idx) { StringBuilder() }.append(it) }
                    tc.optJSONObject("function")?.let { fn ->
                        fn.optString("name").takeIf { it.isNotBlank() }?.let { pendingNames.getOrPut(idx) { StringBuilder() }.append(it) }
                        fn.optString("arguments").takeIf { it.isNotBlank() }?.let { pendingArgs.getOrPut(idx) { StringBuilder() }.append(it) }
                    }
                    tc.optString("arguments").takeIf { it.isNotBlank() }?.let { pendingArgs.getOrPut(idx) { StringBuilder() }.append(it) }
                }
            }
            // finish_reason=tool_calls:参数流结束,逐个发完整 ToolCall
            if (c.optString("finish_reason") == "tool_calls") {
                events += flushPendingCalls()
            }
        }
        // 非流式 message.tool_calls:一次性发出
        if (delta == null) {
            c.optJSONObject("message")?.optJSONArray("tool_calls")?.let { calls ->
                for (i in 0 until calls.length()) {
                    val tc = calls.optJSONObject(i) ?: continue
                    val fn = tc.optJSONObject("function") ?: continue
                    events += AgentEvent.ToolCall(
                        callId = tc.optString("id").ifBlank { syntheticId(fn.optString("name")) },
                        tool = fn.optString("name"),
                        input = summarizeArgs(fn.optString("arguments")),
                    )
                }
            }
        }
        return events
    }

    /** 参数流结束:把累积的调用逐个 upsert 成卡片(input 为完整参数摘要)。 */
    private fun flushPendingCalls(): List<AgentEvent> {
        val events = mutableListOf<AgentEvent>()
        for (idx in (pendingIds.keys + pendingNames.keys + pendingArgs.keys).toSortedSet()) {
            if (idx in finishedCalls) continue
            val name = pendingNames[idx]?.toString().orEmpty()
            if (name.isBlank()) continue
            finishedCalls += idx
            events += AgentEvent.ToolCall(
                callId = pendingIds[idx]?.takeIf { it.isNotBlank() } ?: syntheticId(name),
                tool = name,
                input = summarizeArgs(pendingArgs[idx]?.toString().orEmpty()),
            )
        }
        return events
    }

    /* ---------------- 输入/输出摘要 ---------------- */

    /**
     * 工具输入摘要:卡片首行展示的关键信息。
     * 路径类参数取值本体,命令取 command,其余 JSON 截断。
     */
    private fun summarizeInput(input: JSONObject?): String {
        input ?: return ""
        val key = listOf("path", "paths", "command", "pattern", "url", "query", "prompt", "goal").firstOrNull { input.has(it) }
        if (key != null) {
            val v = input.opt(key)
            return when (v) {
                is JSONArray -> (0 until v.length()).joinToString(", ") { v.optString(it) }.take(120)
                else -> v.toString().take(120)
            }
        }
        return input.toString().take(120)
    }

    private fun summarizeArgs(args: String): String {
        if (args.isBlank()) return ""
        val obj = try {
            JSONObject(args)
        } catch (e: Exception) {
            return args.take(120)
        }
        return summarizeInput(obj)
    }

    /** 工具输出摘要:对象取 output/summary/content 字段,否则原样截断。 */
    private fun summarizeOutput(output: Any?): String = when (output) {
        null -> ""
        is String -> output.take(300)
        is JSONObject -> {
            val key = listOf("output", "summary", "content", "value", "text").firstOrNull { output.has(it) }
            if (key != null) output.opt(key).toString().take(300) else output.toString().take(300)
        }
        else -> output.toString().take(300)
    }

    private fun syntheticId(tool: String): String {
        val n = nextOrder++
        callOrder["synthetic-$n"] = n
        return "synthetic-$n-$tool"
    }
}

/** 解析器内部的特殊工具名前缀(与 core:model 的 ToolCard.SUBAGENT_PREFIX 对应)。 */
object ToolCardMarker {
    const val SUBAGENT = "subagent:"
}
