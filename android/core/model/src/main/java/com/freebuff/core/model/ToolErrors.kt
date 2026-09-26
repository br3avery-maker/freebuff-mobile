package com.freebuff.core.model

/**
 * 回传给模型的「工具错误」信封。
 *
 * 为什么单独成模块:工具报错是模型唯一能拿来纠错的信号。业界共识(Anthropic《Writing effective
 * tools for agents》的 error-response 一节、Claude 工具文档的 is_error 说明、MCP 工具设计规范)是:
 * 错误响应必须**具体且可执行** —— 说清 错误类型 / 问题出在哪个参数 / 怎么改(最好直接给正确调用示例),
 * 而不是丢一个错误码或堆栈。Anthropic 明确写了:错误的 tool_result 要「写清哪里错了、接下来该试什么」,
 * 模型会据此自我纠正 2–3 次;而「Error 500」这类文案只会让它盲目重试。
 *
 * 实测痛点(本项目):旧文案是「缺少参数 query」「工具执行失败:xxx」这类**没有处方**的话,
 * 弱模型只会原地重发同样的调用(或干脆卡住),强模型也只能猜 —— 一轮工具调用白费。
 *
 * 信封格式(固定形状,模型容易学会、端上单测也能断言):
 * ```
 * [工具错误] 类型=<中文名>/<TAG> · 工具=<name>[ · 第 N 次失败]
 * 问题: <具体哪里不对,含收到的参数>
 * 怎么改: <可执行建议,必要时给正确调用示例>
 * ```
 * 同一工具连续失败时追加一句「别重复同样的调用」——这是有界自纠(重试一次就换路),
 * 避免弱模型把循环耗在同一个错误上。
 */
object ToolErrors {

    /** 固定前缀:模型与端上测试都靠它识别「这是一条错误结果」。 */
    const val MARK = "[工具错误]"

    /** 同一工具连续失败到第几次开始劝「换路」。第一次失败仍鼓励按建议重试一次。 */
    const val ESCALATE_AT = 2

    private const val MAX_PROBLEM = 200
    private const val MAX_FIX = 260

    /**
     * 错误分类。
     *
     * @param tag 稳定英文标签(写进信封,模型可据此判断该重试还是该换路)
     * @param label 中文名(弱模型读中文比读英文标签更稳)
     * @param retryable 是否值得按建议重试一次
     */
    enum class Kind(val tag: String, val label: String, val retryable: Boolean) {
        INVALID_ARGS("INVALID_ARGS", "参数不是合法 JSON", true),
        MISSING_PARAM("MISSING_PARAM", "缺少必填参数", true),
        UNKNOWN_PARAM("UNKNOWN_PARAM", "参数名不存在", true),
        BAD_VALUE("BAD_VALUE", "参数取值不合法", true),
        UNKNOWN_TOOL("UNKNOWN_TOOL", "工具不存在", false),
        NOT_FOUND("NOT_FOUND", "没找到对应资源", true),
        AUTH("AUTH", "鉴权或权限不足", false),
        RATE_LIMIT("RATE_LIMIT", "请求过于频繁", true),
        NETWORK("NETWORK", "服务或网络不可用", true),
        DENIED("DENIED", "用户不允许执行", false),
        INTERNAL("INTERNAL", "工具内部出错", false),
    }

    /** 参数层面的一条问题(交给 [forProblems] 变成给模型看的信封)。 */
    sealed interface ArgProblem {
        fun describe(): String

        /** 必填参数缺失。 */
        data class Missing(val name: String) : ArgProblem {
            override fun describe() = "必填参数 " + name + " 没有给"
        }

        /** 参数名不在 schema 里(弱模型常自造参数名,如把 path 写成 file)。 */
        data class Unknown(val name: String) : ArgProblem {
            override fun describe() = "参数名 " + name + " 不存在(可能你想用的是别的名字)"
        }

        /** 取值不在闭集里。 */
        data class Enum(val name: String, val value: String, val allowed: List<String>) : ArgProblem {
            override fun describe() = "参数 " + name + " 的取值「" + value + "」不在允许范围内"
        }

        /** 数值越界。 */
        data class Range(val name: String, val value: String, val min: Int?, val max: Int?) : ArgProblem {
            override fun describe() = "参数 " + name + " 的取值「" + value + "」超出范围"
        }

        /** 类型不对且无法自动归一。 */
        data class Type(val name: String, val value: String, val expected: String) : ArgProblem {
            override fun describe() = "参数 " + name + " 需要 " + expected + " 类型,收到的是「" + value + "」"
        }
    }

    /** 组装错误信封。 */
    fun report(
        kind: Kind,
        tool: String,
        problem: String,
        fix: String,
        attempt: Int = 1,
        example: String = "",
    ): String = buildString {
        append(MARK).append(" 类型=").append(kind.label).append('/').append(kind.tag)
        if (tool.isNotBlank()) append(" · 工具=").append(tool)
        if (attempt > 1) append(" · 第 ").append(attempt).append(" 次失败")
        append('\n').append("问题: ").append(oneLine(problem, MAX_PROBLEM))
        append('\n').append("怎么改: ").append(oneLine(fix, MAX_FIX))
        if (example.isNotBlank()) append('\n').append("正确调用示例: ").append(oneLine(example, MAX_FIX))
        if (attempt >= ESCALATE_AT) append('\n').append(ESCALATE)
    }

    /** 同一工具连续出错时的追加提醒(有界自纠:别把循环耗在同一个错误上)。 */
    const val ESCALATE =
        "注意:该工具已连续失败两次,不要再发同样的调用 —— 换参数、换工具,或直接告诉用户卡在哪里。"

    /**
     * 给已有的错误信封补上「连续失败」升级语。
     *
     * 为什么要这个兜底:错误分支很多(未知工具/参数体检/HTTP/内部异常/记忆类工具…),
     * 不是每一处都拿得到「第几次失败」。执行器在返回前统一过一道,
     * 保证「同一工具第 2 次失败一定劝换路」这条不变式永远成立。
     */
    fun escalateIfNeeded(content: String, attempt: Int): String =
        if (attempt < ESCALATE_AT || content.contains(ESCALATE)) content else content + "\n" + ESCALATE

    /**
     * 参数校验结果 → 信封。只讲第一条问题(讲太多弱模型会抓不住),但把合法参数名与示例都给出来,
     * 让它下一次能一次改对。
     */
    fun forProblems(tool: AgentTool, problems: List<ArgProblem>, attempt: Int = 1): String {
        val first = problems.firstOrNull() ?: return ""
        val kind = when (first) {
            is ArgProblem.Missing -> Kind.MISSING_PARAM
            is ArgProblem.Unknown -> Kind.UNKNOWN_PARAM
            is ArgProblem.Enum -> Kind.BAD_VALUE
            is ArgProblem.Range -> Kind.BAD_VALUE
            is ArgProblem.Type -> Kind.BAD_VALUE
        }
        val fix = buildString {
            when (first) {
                is ArgProblem.Missing -> append("补上 ").append(first.name).append(" 后重试一次。")
                is ArgProblem.Unknown -> append("只用 ").append(tool.name).append(" 声明过的参数,重发一次。")
                is ArgProblem.Enum -> append(first.name).append(" 只能取 ").append(first.allowed.joinToString(" / ")).append("。")
                is ArgProblem.Range -> append(first.name).append(" 取值需在 ")
                    .append(first.min?.toString() ?: "不限").append("~").append(first.max?.toString() ?: "不限").append(" 之内。")
                is ArgProblem.Type -> append(first.name).append(" 要传 ").append(first.expected).append("。")
            }
            append("参数清单: ").append(warnParams(tool))
        }
        return report(kind, tool.name, first.describe(), fix, attempt, tool.example)
    }

    /** 工具不存在:给近邻建议 + 完整清单,让模型立刻改对而不是继续编。 */
    fun unknownTool(name: String, known: List<String>): String {
        val near = nearest(name, known)
        val problem = if (near.isEmpty()) {
            "没有名为 " + name + " 的工具(工具名必须来自可用清单)"
        } else {
            "没有名为 " + name + " 的工具;名字接近的有:" + near.joinToString("、")
        }
        val fix = "从可用工具里挑一个重新调用" +
            (if (near.isNotEmpty()) "(优先用「" + near.first() + "」)" else "") +
            "。可用工具: " + known.joinToString("、")
        return report(Kind.UNKNOWN_TOOL, name, problem, fix)
    }

    /** 用户在设置里禁用了该工具。 */
    fun disabledByUser(tool: String): String = report(
        Kind.DENIED, tool,
        "该工具已被用户在设置里禁用",
        "不要重复调用它;改用其它工具完成,或把这一步交给用户自己做",
    )

    /** 用户在确认弹窗里点了「拒绝」。 */
    fun deniedByUser(tool: String): String = report(
        Kind.DENIED, tool,
        "用户拒绝了这次调用",
        "不要重复调用它;换其它方式达成目标,或把需要用户配合的部分直接说清楚",
    )

    /**
     * 完全重复的调用(结果已复用,没有重新执行)。
     * 不是错误,但同样要带上「别再发一遍」的处方 —— 模型重放同一组工具是本项目实测过的主要循环之一。
     */
    fun duplicateNote(tool: String, cached: String): String = buildString {
        append("↺ 这次调用与上一次完全相同,已复用上次结果(没有重新执行)。")
        append("若结果不是你要的,换参数或换工具,不要重发同样的调用。")
        if (cached.isNotBlank()) append("\n\n上次结果:\n").append(cached)
    }

    /**
     * HTTP / 网络类失败:按状态码给不同处方(参考各家 API 的通用语义)。
     * hint 由调用方补工具特有的下一步(如「先用 github_search_repositories 确认 owner/repo」)。
     */
    fun http(tool: String, code: Int, detail: String, attempt: Int = 1, hint: String = ""): String {
        val (kind, fix) = when {
            code == 401 || code == 403 ->
                Kind.AUTH to "该资源需要登录或没有权限;换成公开可访问的资源,或请用户提供授权"
            code == 404 ->
                Kind.NOT_FOUND to "检查名称/路径拼写是否写错,确认资源确实存在后再重试一次"
            code == 429 ->
                Kind.RATE_LIMIT to "被限流了;等一会儿再说,或减少调用次数(不要连续重试)"
            code >= 500 ->
                Kind.NETWORK to "对方服务临时故障;可以等一会儿重试一次,仍失败就换其它来源"
            else -> Kind.INTERNAL to "换其它工具或换参数再试;不要用相同参数反复重试"
        }
        val problem = "HTTP " + code + (if (detail.isBlank()) "" else " - " + detail)
        return report(kind, tool, problem, if (hint.isBlank()) fix else hint + "。" + fix, attempt)
    }

    /** 兜底:工具内部异常(绝不把堆栈丢给模型)。 */
    fun internalError(tool: String, detail: String, attempt: Int = 1): String = report(
        Kind.INTERNAL, tool, detail,
        "换其它工具或换参数再试一次;若还是这个错,直接把情况告诉用户",
        attempt,
    )

    /* ---------------- 内部工具 ---------------- */

    /** "名:类型(必填/可选)" 的紧凑清单:信封里带一份,模型不必回头猜 schema。 */
    private fun warnParams(tool: AgentTool): String =
        tool.params.joinToString("、") { p ->
            p.name + ":" + p.type + (if (p.required) "" else "(可选)")
        }

    /** 单行化 + 截断:信封必须是固定形状,不允许被换行/超长细节冲散。 */
    private fun oneLine(s: String, max: Int): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length > max) t.take(max - 1) + "…" else t
    }

    /** 与可用工具名做近似匹配(别名/包含/字符二元组相似度),只用于「你是不是想用 X」。 */
    private fun nearest(asked: String, known: List<String>, limit: Int = 2): List<String> =
        known.map { it to score(asked.lowercase(), it.lowercase()) }
            .filter { it.second >= 0.5 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }

    private fun score(asked: String, known: String): Double {
        if (asked == known) return 0.0
        if (known.contains(asked) || asked.contains(known)) return 0.8
        val at = asked.split('_', '-').filter { it.length > 2 }
        val kt = known.split('_', '-').filter { it.length > 2 }
        if (at.isNotEmpty() && kt.isNotEmpty()) {
            val shared = at.count { a -> kt.any { k -> k == a } }
            if (shared > 0) return 0.5 + 0.4 * shared / maxOf(at.size, kt.size)
        }
        return dice(asked, known)
    }

    /** Sørensen–Dice 系数(字符二元组):对付拼写相近的名字(如 get_file ↔ get_files)。 */
    private fun dice(a: String, b: String): Double {
        fun bigrams(s: String): Set<String> =
            (0 until maxOf(0, s.length - 1)).map { s.substring(it, it + 2) }.toSet()
        val x = bigrams(a)
        val y = bigrams(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return 2.0 * x.intersect(y).size / (x.size + y.size)
    }
}
