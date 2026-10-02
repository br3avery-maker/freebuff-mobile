package com.freebuff.core.model

import kotlin.math.ceil

/**
 * 上下文工程策略(借鉴 Cline auto-compact 与 LibreChat 会话摘要的公开设计):
 * - [estimateTokens]:轻量 token 估算,驱动预算裁剪与压缩触发
 * - [parseCtxWindow]:解析自定义模型 ctx 字段("128k"/"1m"/"200000")
 * - [compressToolResult]:工具结果的机械压缩——保留首尾、丢弃中段(有损但保留边界信息)
 *
 * 纯函数,无 Android 依赖;单测见 ContextPolicyTest。
 */
object ContextPolicy {

    /** 未声明上下文窗口时的保守默认值(避免把长历史塞进小窗口模型)。 */
    const val DEFAULT_CONTEXT_WINDOW = 32768

    /** 单条工具结果的最大保留 token(超出即中段压缩)。 */
    const val TOOL_RESULT_TOKEN_CAP = 1200

    /**
     * 估算一段文本的 token 数。
     * 保守启发式:中日韩字符按 1 token/字符(现代分词器实际约 0.6~1,取上界防溢出),
     * 其他字符按 ~3.3 字符/token。误差 ±20%,只用于预算决策而非计费。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        for (ch in text) {
            if (ch.code > 0x2E00) cjk++ // CJK 统一表意文字及扩展区起点
        }
        val other = text.length - cjk
        return ceil(cjk * 1.0 + other * 0.3).toInt()
    }

    /**
     * 解析上下文窗口声明。
     * 支持 "128k"/"1m"/"8K"/"200000";空白/非法返回 [DEFAULT_CONTEXT_WINDOW]。
     */
    fun parseCtxWindow(raw: String): Int {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return DEFAULT_CONTEXT_WINDOW
        val m = Regex("^(\\d+(?:\\.\\d+)?)([km]?)$").find(s) ?: return DEFAULT_CONTEXT_WINDOW
        val n = m.groupValues[1].toDoubleOrNull() ?: return DEFAULT_CONTEXT_WINDOW
        val mult = when (m.groupValues[2]) {
            "k" -> 1000.0
            "m" -> 1000000.0
            else -> 1.0
        }
        val v = (n * mult).toInt()
        return if (v in 1024..2_000_000) v else DEFAULT_CONTEXT_WINDOW
    }

    /**
     * 机械压缩工具结果:超预算时保留头部 [headChars] 与尾部 [tailChars],
     * 中段替换为省略标记(保留数据边界——开头通常是查询回显,结尾通常是结论)。
     * 未超限时原样返回(无损)。
     */
    fun compressToolResult(text: String, maxTokens: Int = TOOL_RESULT_TOKEN_CAP): String {
        if (estimateTokens(text) <= maxTokens) return text
        val headChars = (maxTokens * 2).coerceIn(200, 3000) // 工具结果以 ASCII 为主,按 0.3 token/char 反推
        val tailChars = headChars / 3
        if (text.length <= headChars + tailChars + 40) return text
        val dropped = text.length - headChars - tailChars
        return text.take(headChars) +
            "\n…[About $dropped characters omitted. Call the tool with more precise arguments for details.]…" +
            text.takeLast(tailChars)
    }

    /** 提取式摘要回退(LLM 摘要失败时):保留首条用户消息 + 末条回复要点。 */
    fun extractiveSummary(userText: String, agentText: String, turns: Int): String =
        buildString {
            append("(Auto-summary · $turns earlier messages)\n")
            val u = userText.trim().take(300)
            if (u.isNotEmpty()) append("Original request: ").append(u).append('\n')
            val a = agentText.trim().take(400)
            if (a.isNotEmpty()) append("Latest progress: ").append(a)
        }.trim()
}

/**
 * 一次对话的 token 预算。
 * @param contextWindow 模型声明的上下文窗口
 * @param reserveOutput 为模型输出预留的空间(DeepSeek 等默认 4k)
 */
data class ContextBudget(
    val contextWindow: Int,
    val reserveOutput: Int = 4096,
) {
    /** 可用于「系统提示 + 历史」的 token 总额。 */
    val usableTokens: Int get() = (contextWindow - reserveOutput).coerceAtLeast(2048)

    /** 触发 LLM 摘要压缩的阈值:占用量超过可用额的 70%(机械裁剪兜不了底时提前压缩)。 */
    val compactionThreshold: Int get() = (usableTokens * 0.7).toInt()

    fun isOver(totalTokens: Int): Boolean = totalTokens > usableTokens
}

/** 一次上下文构建的统计(调试/展示用)。 */
data class ContextStats(
    val systemTokens: Int = 0,
    val historyTurns: Int = 0,
    val keptTurns: Int = 0,
    val compactedTurns: Int = 0,
    val truncatedToolResults: Int = 0,
    val summaryTokens: Int = 0,
    val totalTokens: Int = 0,
) {
    /** 是否发生了摘要压缩(供会话页提示「已压缩早期上下文」)。 */
    val didCompact: Boolean get() = compactedTurns > 0
}
