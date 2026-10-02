package com.freebuff.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具参数 JSON 的宽容解析与修复。
 *
 * 为什么需要(都是实测踩到 / 上游 issue 记录在案的):
 * - **空参数**:Anthropic 经 OpenAI 兼容层返回 `"arguments": ""`(参数为空的工具),按「必须能解析」
 *   判断会把这次调用判为未完成,工具永远不执行、流提前结束 —— vercel/ai#6687 就是这个;
 * - **带栅栏/尾逗号/单引号/智能引号**:模型手写 JSON 时的常见瑕疵,直接 `JSONObject()` 会抛异常,
 *   一轮工具调用白费;
 * - **前文夹带**("好的,参数是:{...}")与**参数被包成对象**(个别网关不发字符串而发 JSON 对象)。
 *
 * 策略:先原样解析;失败逐级修复;仍失败返回 [Result.Invalid] 并把原始片段带上,
 * 由执行器回一条「参数不是合法 JSON,请重试」给模型(可自愈),而不是抛异常中断整轮。
 */
object JsonArgs {

    /** 解析结果:成功给出对象,失败给出可回传给模型的诊断文本。 */
    sealed interface Result {
        /** 解析成功(空参数归一为 {})。 */
        data class Ok(val obj: JSONObject, val repaired: Boolean = false) : Result

        /** 无法修复:raw 是原始片段(截断),reason 是给模型的说明。 */
        data class Invalid(val reason: String, val raw: String) : Result

        /** 便捷取值(失败时为 null)。 */
        val objectOrNull: JSONObject? get() = (this as? Ok)?.obj
    }

    /** 参数文本最大处理长度(超长直接截断,避免异常长文本拖慢解析)。 */
    private const val MAX_LEN = 200_000

    /** 解析工具参数:空串/空白视为 `{}`。 */
    fun parse(raw: String?): Result {
        val text = (raw ?: "").trim()
        if (text.isEmpty()) return Result.Ok(JSONObject())
        if (text.length > MAX_LEN) return Result.Invalid("Arguments too long (> ${MAX_LEN} characters)", text.take(300))
        // 1) 原样(但键名里带智能引号时视为「引号用错了」—— org.json 宽容到会把 “key” 当成键,
        //    这样解析出来的对象其实没有模型想要的字段,必须送去修复)
        asObject(text)?.let { if (!hasSmartQuotedKey(it)) return Result.Ok(it) }
        // 2) 逐级修复
        val fixed = repair(text)
        if (fixed != text) {
            asObject(fixed)?.let { return Result.Ok(it, repaired = true) }
            asArray(fixed)?.let { return Result.Ok(JSONObject().put("items", it), repaired = true) }
        }
        asArray(text)?.let { return Result.Ok(JSONObject().put("items", it)) }
        return Result.Invalid("Arguments must be a valid JSON object", text.take(300))
    }

    /**
     * 修复常见瑕疵(顺序即优先级):
     * 去掉 markdown 栅栏 → 截取最外层花括号 → 智能引号转直引号 → 去尾逗号 →
     * 无直双引号时单引号转双引号(同时加点号键名)。
     */
    fun repair(raw: String): String {
        var s = raw.trim()
        // ```json {…} ```
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            val end = s.lastIndexOf("```")
            if (end >= 0) s = s.substring(0, end).trim()
        }
        // 前后夹带说明文字:取第一个 { / [ 到最后一个 } / ]
        val firstBrace = s.indexOfFirst { it == '{' || it == '[' }
        val lastBrace = s.indexOfLast { it == '}' || it == ']' }
        if (firstBrace > 0 || (lastBrace in 0 until s.length - 1)) {
            if (firstBrace >= 0 && lastBrace > firstBrace) s = s.substring(firstBrace, lastBrace + 1)
        }
        // 智能引号(中文输入法/复制粘贴常见)
        s = s.replace('\u201c', '"').replace('\u201d', '"')
            .replace('\u2018', '\'').replace('\u2019', '\'')
        // 尾逗号:{a:1,} / [1,2,]
        s = Regex(",\\s*([}\\]])").replace(s) { it.groupValues[1] }
        // 单引号 JSON:只在完全没有双引号时转换(JSON 字符串不允许单引号,
        // 有双引号时说明是正常 JSON + 字符串里带 ' ,不能动)
        if (!s.contains('"') && s.contains('\'')) {
            s = Regex("'([^']*)'").replace(s) { m -> "\"" + m.groupValues[1] + "\"" }
        }
        return s
    }

    private fun hasSmartQuotedKey(o: JSONObject): Boolean = try {
        o.keys().asSequence().any { k -> k.any { it == '\u201c' || it == '\u201d' } }
    } catch (e: Exception) {
        false
    }

    private fun asObject(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (e: Exception) {
        null
    }

    private fun asArray(text: String): JSONArray? = try {
        JSONArray(text)
    } catch (e: Exception) {
        null
    }
}
