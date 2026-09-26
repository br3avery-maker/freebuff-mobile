package com.freebuff.core.model

/**
 * agent 循环的延续策略(纯函数,便于单测)。
 *
 * 取开源生态已经验证过的三条规则:
 * 1. **还在调工具就继续**(Cline / OpenHands 的回合制循环);
 * 2. **显式完成才收工**——模型调用 [COMPLETION_TOOL] / end_turn 才算完成
 *    (Cline 的 attempt_completion;OpenHands 的 FinishTool);
 * 3. **已经动过工具却突然只回文本**(进度播报)时不收工,而是提醒一次继续
 *    (对应 Codex CLI「承诺了下一步却提前收工」这类缺陷的修法),提醒有次数上限。
 *
 * 轮次上限只是兜底:给足预算,不再像早期那样 6 轮就硬停。
 */
object AgentLoop {

    /** 单次对话最多执行的工具轮数(模型→工具→模型 记一轮)。 */
    const val MAX_TOOL_ROUNDS = 30

    /** 文本回合的「继续」提醒上限(超过后交给用户,不再自动往下跑)。 */
    const val MAX_CONTINUE_NUDGES = 3

    /** 模型声明任务完成所用的工具(与后端 agent 运行时的 task_completed 对应)。 */
    const val COMPLETION_TOOL = "task_completed"

    /** 视为「本回合可以收工」的工具。 */
    val END_TOOLS = setOf(COMPLETION_TOOL, "end_turn")

    /** 提醒模型继续时的系统消息(进协议、不进可见正文)。 */
    const val NUDGE_TEXT = "你上一轮只输出了文字,没有调用任何工具,但任务还没有声明完成。" +
        "请继续推进:调用工具完成下一步;若确实已全部完成,调用 " + COMPLETION_TOOL + " 并写好 summary。" +
        "不要只描述计划,也不要重复已经说过的内容。"

    /** 轮次上限的可见提示(追加到正文,让用户知道为什么停了以及怎么继续)。 */
    const val ROUND_LIMIT_NOTE = "\n\n(已达 $MAX_TOOL_ROUNDS 轮工具调用上限,已停止继续执行;回复「继续」可以接着做)"

    sealed interface Decision {
        /** 继续下一轮。 */
        object Continue : Decision

        /** 正常收工(模型答完/声明完成/未开工具)。 */
        object Stop : Decision

        /** 提醒模型继续(注入系统消息后进入下一轮)。 */
        data class Nudge(val message: String) : Decision

        /** 打到轮次上限:停止并在正文里注明。 */
        data class StopWithNote(val note: String) : Decision
    }

    /**
     * @param toolRounds 已执行的工具轮数
     * @param toolCalls 本轮模型请求的工具调用数
     * @param toolsEnabled 请求是否携带了工具定义
     * @param nudgesUsed 已经提醒过几次
     * @param completed 本轮是否调用了完成工具
     */
    fun decide(
        toolRounds: Int,
        toolCalls: Int,
        toolsEnabled: Boolean,
        nudgesUsed: Int,
        completed: Boolean = false,
    ): Decision = when {
        completed -> Decision.Stop
        !toolsEnabled -> Decision.Stop
        toolRounds >= MAX_TOOL_ROUNDS -> Decision.StopWithNote(ROUND_LIMIT_NOTE)
        toolCalls > 0 -> Decision.Continue
        toolRounds == 0 -> Decision.Stop
        nudgesUsed < MAX_CONTINUE_NUDGES -> Decision.Nudge(NUDGE_TEXT)
        else -> Decision.Stop
    }
}
