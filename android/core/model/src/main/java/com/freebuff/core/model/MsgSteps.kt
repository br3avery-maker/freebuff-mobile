package com.freebuff.core.model

/**
 * 消息内「状态牌」步骤的命名与判定(纯逻辑,便于单测)。
 *
 * 过程性步骤(连接、第 N 轮)只是让用户看到「还活着、在做什么」,
 * 收尾时必须清掉 —— 否则对话结束后会留下一条过期的「连接模型并开始生成…」。
 * 历史性步骤(如「重试 n」)保留。
 */
object MsgSteps {

    /** 起手步骤:连接模型。 */
    const val CONNECT = "连接"

    /** 用户按下停止、且一个字都没生成时填入气泡(否则会留下一个空回复,看上去像坏了)。 */
    const val STOPPED_NOTE = "(已停止生成)"

    /** 用户按下停止、但已有部分内容时的尾部标注。 */
    const val STOPPED_TAIL = "\n\n(已停止生成,以上为已经写出的内容)"

    /** 模型什么文字都没返回、也没有工具卡片时的提示(否则会留下一个神秘的空回复)。 */
    const val EMPTY_REPLY_NOTE = "(模型没有返回内容;可直接重试,或换一个模型)"

    /** 生成中的占位时间(不是真实时刻):冷启动修复时清掉,免得历史里永久挂着一个假时间戳。 */
    const val GENERATING = "正在生成"

    /**
     * 上一次生成被中断(进程被杀/崩溃)时,那条半成品消息应显示的提示。
     * 它和 EMPTY_REPLY_NOTE 的区别:后者是模型真的答了空,前者是我们没写完就没了。
     */
    const val INTERRUPTED_NOTE = "(上次生成被中断,可重新发送)"

    /**
     * 冷启动修复:修掉上一次进程被杀时留在库里的「半条消息」。
     *
     * 为什么需要:流式回复是边收边写库的,进程被杀时那条消息就停在半途 ——
     * 正文为空、步骤停在「连接模型并开始生成…」、工具卡片停在 running/waiting。
     * 重启后它看起来像「还在生成」却永远不动(真机实测:用户会话里挂着这样一条);
     * 空正文还会作为一条空助手轮进入后续上下文。
     *
     * 只在进程启动时调用 —— 那一刻库里不可能有真在流式写入的消息,因此可以安全地
     * 把所有消息都过一遍:去掉过程性步骤、给空正文补中断提示、把没跑完的工具卡片标成未完成。
     */
    fun repairAbandoned(msg: ChatMsg): ChatMsg = when {
        msg.role != "agent" -> msg
        else -> msg.copy(
            text = if (msg.text.isBlank()) INTERRUPTED_NOTE else msg.text,
            time = if (msg.time == GENERATING) "" else msg.time,
            steps = withoutTransient(msg.steps),
            tools = msg.tools.map { card ->
                if (card.isRunning || card.isWaiting) {
                    card.copy(state = "error", output = if (card.output.isBlank()) "(未完成)" else card.output)
                } else card
            },
        )
    }

    /**
     * 停止后的正文:空则给提示,非空则加尾标注(不覆盖已写出的内容)。
     *
     * 幂等:「用户按停止」(stopStreaming)与「循环里发现本轮被中断」两条收尾路径
     * 都会调用它,而两者的执行顺序不确定(实测两条日志相差 30ms),重复调用不得叠加标注。
     */
    fun stoppedText(existing: String): String {
        val t = existing.trimEnd()
        return when {
            existing.isBlank() -> STOPPED_NOTE
            t.endsWith(STOPPED_NOTE) || t.endsWith(STOPPED_TAIL.trim()) -> existing
            else -> t + STOPPED_TAIL
        }
    }

    private const val ROUND_PREFIX = "第 "
    private const val RETRY_PREFIX = "重试 "

    /** 第 n 轮工具循环。 */
    fun round(n: Int): String = "$ROUND_PREFIX$n 轮"

    /** 本轮在做什么:无调用 = 生成回答;有调用 = 列出工具名。 */
    fun calling(tools: List<String>): String =
        if (tools.isEmpty()) "正在生成回答…" else "调用 " + tools.joinToString("、")

    /** 重试步骤(历史性,收尾保留)。 */
    fun retry(n: Int): String = "$RETRY_PREFIX$n"

    /** 过程性步骤:收尾时清除(重试记录是历史性的,保留)。 */
    fun isTransient(name: String): Boolean = when {
        name.startsWith(RETRY_PREFIX) -> false
        name == CONNECT -> true
        name.startsWith(ROUND_PREFIX) -> true
        else -> false
    }

    /** 收尾后应保留的步骤。 */
    fun withoutTransient(steps: List<MsgStep>): List<MsgStep> = steps.filterNot { isTransient(it.name) }

    /**
     * 收尾提示附带的执行结果摘要(每条取结果首行)。
     *
     * 为什么要它:模型重放(连续重复调用同一组工具)被停下时,正文往往是空的 ——
     * 用户看到的「回复」就只有几张工具卡加一句停因,自己问的答案反而看不到。
     */
    fun digest(rows: List<Pair<String, String>>, maxCharsPerLine: Int = 160): String {
        if (rows.isEmpty()) return ""
        return rows.joinToString("\n", prefix = "\n\n最近一轮的执行结果:\n") { (name, out) ->
            "· $name:" + out.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().take(maxCharsPerLine)
        }
    }
}
