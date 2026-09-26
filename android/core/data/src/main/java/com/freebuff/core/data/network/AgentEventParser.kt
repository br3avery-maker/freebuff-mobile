package com.freebuff.core.data.network

import com.freebuff.core.model.AgentEvent
import com.freebuff.core.model.JsonArgs
import com.freebuff.core.model.ToolCallReq
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 事件流解析器(有状态)。输入一个**完整事件**的 data 载荷,输出零个或多个 [AgentEvent]。
 *
 * 解析侧适配(对着开源生态里踩过的坑逐条补):
 *
 * 1. **工具调用槽位按 id 优先、index 兜底** —— 规范里 delta.tool_calls 靠 `index` 归并,
 *    但 Gemini 的 OpenAI 兼容层**不发 index**;此时若按数组下标归并,并行两个调用会被合成一个
 *    (NousResearch/hermes-agent#62937 就是这个现象)。有 index 用 index,没有就按 id 分槽,
 *    两者都没有才归到最后一次出现的调用上(OpenAI 允许 name/参数分片到达,见 crush#3153)。
 * 2. **参数空串视为 {}** —— Anthropic 经兼容层对「无参数工具」返回 `"arguments": ""`,
 *    按「必须能解析」判断会被当成未完成、工具永不执行、流提前结束(vercel/ai#6687)。空串归零。
 * 3. **参数可以是字符串,也可以是对象/数组** —— 个别网关直接发结构化参数;同时参数常带栅栏、
 *    尾逗号、单引号等瑕疵,统一交给 [JsonArgs] 宽容解析(修复不了就原样带给执行器报错重试)。
 * 4. **文本/思考内容可能是数组部件** —— Anthropic 兼容网关会发
 *    `content: [{"type":"text","text":"…"}]`;`optString` 遇到数组会静默变空,这里按部件拼接。
 * 5. **HTTP 200 里夹错误帧** —— 部分网关把错误塞进正常流:`{"error": {...}}`;不识别就会
 *    「流在跑但什么都不吐」直到空闲看门狗超时,所以这里显式转成 [AgentEvent.Failure]。
 * 6. **遗留 function_call** —— 老协议 `delta.function_call`(单数)仍被不少网关实现。
 *
 * 另外:Freebuff 原生事件帧(type=...)与 OpenAI 兼容流可混流,未知结构一律静默忽略。
 */
class AgentEventParser {

    /** 一个工具调用的累积槽位。 */
    private class Acc {
        var id: String = ""
        val name = StringBuilder()
        val args = StringBuilder()
        var closed = false
        var seq = 0
    }

    /** 槽位表:key 由 id/index 归一而来,保持插入顺序(= 调用顺序)。 */
    private val accs = linkedMapOf<String, Acc>()

    /** 已落定(已发过 ToolCall 卡片)的槽位 key:参数流结束/重复 finish_reason 时不要重复发卡片。 */
    private val closedKeys = mutableSetOf<String>()

    /** 子代理卡片累积:key = "$agent|$msgId" → 已收输出。 */
    private val subagentBuffers = mutableMapOf<String, StringBuilder>()
    private var seqCounter = 0

    /**
     * 解析一个完整事件载荷。
     * @return 事件列表,按到达顺序;未知/空结构返回空列表
     */
    fun feed(data: String): List<AgentEvent> {
        val payload = data.trim()
        if (payload.isEmpty() || payload == "[DONE]") return emptyList()
        val obj = try {
            JSONObject(payload)
        } catch (e: Exception) {
            return emptyList()
        }
        // 顶层错误帧(HTTP 200 内夹错误):{"error": {...}} —— 必须先于结构分派判断
        if (!obj.has("choices") && obj.has("error") && !obj.has("type")) {
            return listOf(AgentEvent.Failure(errorText(obj.opt("error"))))
        }
        return when {
            obj.has("type") -> parseTyped(obj)
            obj.has("choices") -> parseOpenAi(obj)
            else -> emptyList()
        }
    }

    /**
     * 流结束时调用:把参数累积中仍未落定的工具调用落成卡片。
     * 兜底部分端点不发 finish_reason=tool_calls 的情况。
     */
    fun flush(): List<AgentEvent> = flushPendingCalls()

    /**
     * 流结束后取走本轮全部结构化工具调用(完整参数 JSON + callId),供 agent 循环执行与回传。
     * 取走后解析器内部状态清零,可安全复用于下一轮请求。
     * 与 [flush] 的卡片事件一一对应:flush 发卡片,drain 喂循环。
     */
    fun drainCalls(): List<ToolCallReq> {
        val out = accs.values.toList()
            .filter { it.name.isNotBlank() }
            .map { a ->
                ToolCallReq(
                    callId = a.id.ifBlank { "call-" + a.seq + "-" + a.name },
                    name = a.name.toString(),
                    argsJson = normalizedArgs(a.args.toString()),
                )
            }
        accs.clear()
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
                val msg = errorText(obj.opt("error") ?: obj.optString("message"))
                if (msg.isEmpty()) emptyList() else listOf(AgentEvent.Failure(msg))
            }

            // 提示帧:网关/后端的非致命提醒(如「端点不支持工具,已按纯对话继续」)
            "notice", "info" -> {
                val msg = obj.optString("text", obj.optString("message"))
                if (msg.isEmpty()) emptyList() else listOf(AgentEvent.Notice(msg))
            }

            "subagent-response-chunk", "subagent-chunk" -> {
                val agent = obj.optString("agentType", obj.optString("agent", "subagent"))
                val msgId = obj.optString("agentId", obj.optString("messageId", agent))
                val chunk = obj.optString("text", obj.optString("chunk"))
                if (chunk.isEmpty()) return emptyList()
                val key = "$agent|$msgId"
                val buf = subagentBuffers.getOrPut(key) { StringBuilder() }
                buf.append(chunk)
                listOf(AgentEvent.ToolCall("subagent-$key", ToolCardMarker.SUBAGENT + agent, buf.toString()))
            }

            "subagent-response", "subagent-result" -> {
                val agent = obj.optString("agentType", obj.optString("agent", "subagent"))
                val msgId = obj.optString("agentId", obj.optString("messageId", agent))
                val callId = "subagent-$agent|$msgId"
                val full = obj.optString("text", obj.optString("output"))
                listOf(AgentEvent.ToolResult(callId, full.ifBlank { subagentBuffers[callId]?.toString().orEmpty() }))
            }

            // 思考(思维链)帧:后端 thinker 子代理与部分网关用独立 type 下发
            "reasoning", "reasoning-chunk", "thinking" -> {
                val chunk = obj.optString("text", obj.optString("chunk", obj.optString("reasoning_content")))
                if (chunk.isEmpty()) emptyList() else listOf(AgentEvent.Reasoning(chunk))
            }

            "end_turn", "turn-end", "done" -> listOf(AgentEvent.EndTurn)

            else -> emptyList()
        }
    }

    /* ---------------- OpenAI 兼容增量 ---------------- */

    private fun parseOpenAi(obj: JSONObject): List<AgentEvent> {
        val choices = obj.optJSONArray("choices") ?: return emptyList()
        // 心跳帧(choices: [])与 usage-only 帧在此直接跳过
        val c = choices.optJSONObject(0) ?: return emptyList()
        val events = mutableListOf<AgentEvent>()

        val delta = c.optJSONObject("delta")
        val container = delta ?: c.optJSONObject("message")

        // 思考(思维链)增量:先于正文发射(思考在前、正文在后)
        val thinking = container?.let { reasoningText(it) }.orEmpty()
        if (thinking.isNotEmpty()) events += AgentEvent.Reasoning(thinking)

        // 文本增量:delta.content / choices[0].text(completions 风格) / message.content;都可能是数组部件
        val text = when {
            container != null -> textOf(container, "content")
            else -> ""
        }.ifEmpty { textOf(c, "text") }
        if (text.isNotEmpty()) events += AgentEvent.Text(text)

        if (delta != null) {
            // 现代协议:tool_calls[](可多个,可跨分片)
            delta.optJSONArray("tool_calls")?.let { calls ->
                for (i in 0 until calls.length()) {
                    val tc = calls.optJSONObject(i) ?: continue
                    accumulate(tc, fallbackIndex = if (calls.length() == 1) null else i)
                }
            }
            // 遗留协议:function_call(单数)
            delta.optJSONObject("function_call")?.let { accumulateLegacy(it) }
        } else {
            // 非流式:message.tool_calls / message.function_call 一次性发出
            c.optJSONObject("message")?.let { m ->
                m.optJSONArray("tool_calls")?.let { calls ->
                    for (i in 0 until calls.length()) {
                        val tc = calls.optJSONObject(i) ?: continue
                        val fn = tc.optJSONObject("function") ?: continue
                        val name = fn.optString("name")
                        if (name.isBlank()) continue
                        events += AgentEvent.ToolCall(
                            callId = tc.optString("id").ifBlank { syntheticId(name) },
                            tool = name,
                            input = summarizeArgs(argText(fn.opt("arguments"))),
                        )
                    }
                }
                m.optJSONObject("function_call")?.let { fn ->
                    val name = fn.optString("name")
                    if (name.isNotBlank()) {
                        events += AgentEvent.ToolCall(
                            callId = syntheticId(name),
                            tool = name,
                            input = summarizeArgs(argText(fn.opt("arguments"))),
                        )
                    }
                }
            }
        }

        // finish_reason 归一:tool_calls / function_call 都表示「调用已完整」
        val finish = c.optString("finish_reason")
        if (finish == "tool_calls" || finish == "function_call") events += flushPendingCalls()
        return events
    }

    /* ---------------- 工具调用累积 ---------------- */

    /** 把一个 tool_calls 增量归并进槽位。index 缺失时按 id 分槽(并行调用不会串台)。 */
    private fun accumulate(tc: JSONObject, fallbackIndex: Int? = null) {
        val id = tc.optString("id")
        val index = if (tc.has("index") && !tc.isNull("index")) tc.optInt("index") else fallbackIndex
        val key = when {
            index != null -> "i$index"
            id.isNotBlank() -> "id:$id"
            else -> accs.keys.lastOrNull() ?: "i0"
        }
        val acc = accs.getOrPut(key) { Acc().also { it.seq = seqCounter++ } }
        if (acc.id.isBlank() && id.isNotBlank()) acc.id = id
        val fn = tc.optJSONObject("function")
        mergeFragment(acc.name, tc.optString("name"))
        mergeFragment(acc.name, tc.optString("function_name"))
        if (fn != null) {
            mergeFragment(acc.name, fn.optString("name"))
            appendArgs(acc, argText(fn.opt("arguments")))
        }
        appendArgs(acc, argText(tc.opt("arguments")))
    }

    /** 遗留 `function_call`:按「最后出现的槽位」或新槽位累积。 */
    private fun accumulateLegacy(fn: JSONObject) {
        val key = accs.keys.lastOrNull() ?: "i0"
        val acc = accs.getOrPut(key) { Acc().also { it.seq = seqCounter++ } }
        mergeFragment(acc.name, fn.optString("name"))
        appendArgs(acc, argText(fn.opt("arguments")))
    }

    /**
     * 名称分片合并:分片追加,但**整名重发**不能变成 "web_searchweb_search"
     * (部分网关每个分片都带完整 name)。规则:空→取;相等→跳过;
     * 新片段以已累积内容开头→视为重发,整体替换;否则追加分片。
     */
    private fun mergeFragment(target: StringBuilder, frag: String) {
        if (frag.isBlank()) return
        val cur = target.toString()
        when {
            cur.isEmpty() -> target.append(frag)
            frag == cur -> Unit
            frag.startsWith(cur) -> {
                target.setLength(0)
                target.append(frag)
            }
            else -> target.append(frag)
        }
    }

    /** 参数分片合并:与名称同规则(部分网关整段重发参数)。 */
    private fun appendArgs(acc: Acc, frag: String) {
        if (frag.isBlank()) return
        val cur = acc.args.toString()
        when {
            cur.isEmpty() -> acc.args.append(frag)
            frag == cur -> Unit
            frag.startsWith(cur) -> {
                acc.args.setLength(0)
                acc.args.append(frag)
            }
            else -> acc.args.append(frag)
        }
    }

    /** 参数流的落定:把累积的调用逐个 upsert 成卡片(input 为完整参数摘要)。 */
    private fun flushPendingCalls(): List<AgentEvent> {
        val events = mutableListOf<AgentEvent>()
        accs.entries.toList().forEach { (key, acc) ->
            if (key in closedKeys) return@forEach
            val name = acc.name.toString()
            if (name.isBlank()) return@forEach
            acc.closed = true
            events += AgentEvent.ToolCall(
                callId = acc.id.ifBlank { "call-" + acc.seq + "-" + name },
                tool = name,
                input = summarizeArgs(acc.args.toString()),
            )
            // 标记为已落定(不能把槽位置空:drainCalls 还要按它取回完整参数喂给循环)
            closedKeys += key
        }
        return events
    }

    /* ---------------- 文本 / 思考字段归一 ---------------- */

    /**
     * 取增量里的文本:兼容字符串、数组部件(`content: [{type:text,text:…}]`)
     * 与对象形式(`content: {text: …}`)。
     */
    private fun textOf(o: JSONObject, key: String): String {
        if (!o.has(key) || o.isNull(key)) return ""
        return when (val v = o.opt(key)) {
            is String -> v
            is JSONArray -> (0 until v.length()).joinToString("") { i ->
                when (val item = v.opt(i)) {
                    is String -> item
                    is JSONObject -> item.optString("text", item.optString("content"))
                    else -> ""
                }
            }
            is JSONObject -> v.optString("text", v.optString("content"))
            else -> ""
        }
    }

    /**
     * 取思考文本。字段名各家不同(`reasoning_content` 事实标准 / `reasoning` OpenRouter /
     * `thinking` 部分网关 / `reasoning_text`),值也可能是字符串、对象或数组 —— 统一归一为纯文本。
     */
    private fun reasoningText(o: JSONObject): String {
        for (key in listOf("reasoning_content", "reasoning", "reasoning_text", "thinking")) {
            val s = textOf(o, key)
            if (s.isNotEmpty()) return s
        }
        return ""
    }

    /** 参数取值归一:字符串直接用,对象/数组序列化(个别网关不发字符串)。 */
    private fun argText(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is JSONObject -> v.toString()
        is JSONArray -> v.toString()
        else -> v.toString()
    }

    /** 参数落定前的宽容解析:能修就修成紧凑 JSON,修不了原样给执行器(它会回错误让模型重试)。 */
    private fun normalizedArgs(raw: String): String {
        if (raw.isBlank()) return "{}"
        return when (val r = JsonArgs.parse(raw)) {
            is JsonArgs.Result.Ok -> r.obj.toString()
            is JsonArgs.Result.Invalid -> raw
        }
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
        val obj = JsonArgs.parse(args).objectOrNull ?: return args.take(120)
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

    /** 错误帧文本:支持字符串、{message}/{error}/{detail} 等常见形态。 */
    private fun errorText(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is JSONObject -> listOf("message", "error", "detail", "type")
            .firstNotNullOfOrNull { k -> v.optString(k).takeIf { it.isNotBlank() } }
            ?: v.toString().take(300)
        else -> v.toString().take(300)
    }

    private fun syntheticId(tool: String): String = "synthetic-" + seqCounter++ + "-" + tool
}

/** 解析器内部的特殊工具名前缀(与 core:model 的 ToolCard.SUBAGENT_PREFIX 对应)。 */
object ToolCardMarker {
    const val SUBAGENT = "subagent:"
}
