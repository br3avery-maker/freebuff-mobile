package com.freebuff.core.model

/**
 * 工具重复调用追踪(纯逻辑,便于单测)。
 *
 * 为什么要它:实测某些模型(或中转)在拿到工具结果后会把「同一组工具 + 同一组参数」整轮重放,
 * 模型侧不发 task_completed、正文却已经开始重复吐出同一段话。只靠轮次上限兜底要白烧 30 轮
 * (实测 8 轮 × 3 次抓取,两分多钟),用户界面上表现为永远「正在生成」。
 *
 * 这里的规则与成熟实现一致(Cline 的重复调用检测 / AutoGPT 的结果复用):
 * - 签名 = 工具名 + 归一化参数(只忽略「字符串字面量之外」的空白);
 * - 重复调用**复用上次结果**,不重新执行(无副作用、也不再拖时间);
 * - 连续整轮重复由 [AgentLoop.decide] 提醒一次,再重复就停。
 */
class ToolRepeatTracker {

    private val outputs = mutableMapOf<String, String>()

    /** 调用签名:工具名 + 归一化参数。 */
    fun signature(c: ToolCallReq): String = nameSignature(c.name, c.argsJson)

    /** 供只拿到名字与原始参数文本的调用方复用。 */
    fun nameSignature(name: String, argsJson: String): String = name + "|" + normalize(argsJson)

    /**
     * 参数归一化:去掉字符串字面量之外的空白 ——
     * 模型常在一轮里把参数美化换行、另一轮里压缩成一行,那其实是同一次调用;
     * 但字符串内部的空白可能有意义(表达式 "1 + 1"、URL 里的空格),必须原样保留。
     */
    fun normalize(argsJson: String): String {
        val s = argsJson.trim()
        val out = StringBuilder(s.length)
        var inString = false
        var escaped = false
        for (ch in s) {
            if (inString) {
                out.append(ch)
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when {
                ch == '"' -> { inString = true; out.append(ch) }
                ch.isWhitespace() -> Unit
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /** 该签名此前是否已执行过(即本次属于重复调用)。 */
    fun isRepeat(sig: String): Boolean = outputs.containsKey(sig)

    /** 之前执行过的结果;没执行过返回 null。 */
    fun cachedOutput(sig: String): String? = outputs[sig]

    /** 记录一次真实执行的结果,供后续重复调用复用。 */
    fun remember(sig: String, output: String) {
        outputs[sig] = output
    }

    /** 本次对话已经执行过的不同调用数。 */
    fun distinctCount(): Int = outputs.size
}
