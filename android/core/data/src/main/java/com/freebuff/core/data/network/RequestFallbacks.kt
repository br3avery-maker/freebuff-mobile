package com.freebuff.core.data.network

import org.json.JSONObject

/**
 * 请求侧降级识别:端点明确拒绝「工具定义」或「思考参数」时,重发一次去掉该字段的请求,
 * 而不是把 400 直接甩给用户(很多 OpenAI 兼容端点只实现子集:vLLM/ollama 老版本、
 * 自建网关、只做文本补全的中转站都会因为 `tools` / `enable_thinking` 这类字段直接 400)。
 *
 * 只认「错误文本指向该字段」的情形,其余 400(额度不足、参数写错、鉴权失败)一律照常报错 ——
 * 降级必须窄,否则会把真问题藏起来。
 */
object RequestFallbacks {

    /** 端点不接受 tools / function calling。 */
    fun toolsRejected(code: Int, body: String): Boolean {
        if (!isRejectCode(code)) return false
        val text = errorText(body)
        return mentions(text, TOOL_FIELDS) && mentions(text, REJECT_WORDS)
    }

    /** 端点不认识思考参数(reasoning_effort / enable_thinking / thinking)。 */
    fun reasoningRejected(code: Int, body: String): Boolean {
        if (!isRejectCode(code)) return false
        val text = errorText(body)
        return mentions(text, REASONING_FIELDS) && mentions(text, REJECT_WORDS)
    }

    /** 工具被拒后给用户看的说明。 */
    const val TOOLS_DROPPED_NOTE = "This endpoint rejected tool definitions. Continuing as chat only (tools and automatic multi-step execution unavailable)."

    /** 思考参数被拒后给用户看的说明。 */
    const val REASONING_DROPPED_NOTE = "This endpoint rejected reasoning parameters. Continuing without those fields (returned reasoning is still displayed)."

    private val TOOL_FIELDS = listOf(
        "tools", "tool_choice", "tool_calls", "function calling", "functions",
        // 中文网关的说法(必须同时命中 REJECT_WORDS 才判定,不会误伤)
        "工具调用", "工具定义", "工具参数",
    )
    private val REASONING_FIELDS = listOf(
        "enable_thinking", "reasoning_effort", "reasoning", "thinking", "budget_tokens",
        "思考", "思维链",
    )
    private val REJECT_WORDS = listOf(
        "not support", "unsupported", "does not support", "not supported", "unknown field",
        "unexpected", "invalid", "unrecognized", "not allowed", "不支持", "无法识别", "未知字段", "多余",
    )

    private fun isRejectCode(code: Int): Boolean = code in 400..499 || code == 500

    private fun mentions(text: String, needles: List<String>): Boolean = needles.any { text.contains(it) }

    /** 提取错误文本:JSON 错误体取 message/detail,否则原样。 */
    private fun errorText(body: String): String {
        val raw = body.trim()
        if (raw.isEmpty()) return ""
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return raw.lowercase()
        }
        val err = json.opt("error")
        val msg = when (err) {
            is String -> err
            is JSONObject -> err.optString("message", err.optString("detail", err.toString()))
            else -> json.optString("message", json.optString("detail", raw))
        }
        return msg.lowercase()
    }
}
