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
 * 4. **原地打转要识别**——整轮调用与之前完全相同的工具(同工具同参数)时先提醒,
 *    再重复就直接停(Cline 的重复调用检测;实测某些模型/中转会无限重放同一组工具,
 *    只靠轮次上限会白烧 30 轮、正文也会被重复追加几遍)。
 *    工具结果对重复调用直接复用,不再重新执行(无副作用、也不再拖时间)。
 *
 * 轮次上限只是兜底:给足预算,不再像早期那样 6 轮就硬停。
 */
object AgentLoop {

    /** 单次对话最多执行的工具轮数(模型→工具→模型 记一轮)。 */
    const val MAX_TOOL_ROUNDS = 30

    /** 连续「整轮都在重复调用」达到该轮数后停止(第 1 轮重复先提醒一次)。 */
    const val MAX_REPEAT_ROUNDS = 2

    /** 文本回合的「继续」提醒上限(超过后交给用户,不再自动往下跑)。 */
    const val MAX_CONTINUE_NUDGES = 3

    /** 模型声明任务完成所用的工具(与后端 agent 运行时的 task_completed 对应)。 */
    const val COMPLETION_TOOL = "task_completed"

    /** 视为「本回合可以收工」的工具。 */
    val END_TOOLS = setOf(COMPLETION_TOOL, "end_turn")

    /**
     * 提醒消息的角色:用 user 而不是 system。
     *
     * OpenAI 兼容协议里 system 惯例是「第一条」,中途插 system 各家网关处理不一
     * (llama.cpp 直接忽略、部分网关会当历史重写),而 Cline / OpenHands / Codex CLI
     * 的做法都是把「你还没做完」当成**用户回合的提醒**发出去 —— 兼容性最好。
     * 这里用可见前缀标明它来自系统而非用户本人。
     */
    const val REMINDER_ROLE = "user"

    /** 提醒消息的统一前缀(避免模型把它当成用户本人的新指令)。 */
    const val REMINDER_PREFIX = "(System reminder) "

    /** 提醒模型继续时的消息(进协议、不进可见正文)。 */
    const val NUDGE_TEXT = REMINDER_PREFIX +
        "You only produced text in the previous round without using tools, but the task has not been declared complete. " +
        "Continue: use tools for the next step. If everything is truly complete, call " + COMPLETION_TOOL + " with a clear summary. " +
        "Do not only describe plans or repeat what you have already said."

    /** 轮次上限的可见提示(追加到正文,让用户知道为什么停了以及怎么继续)。 */
    const val ROUND_LIMIT_NOTE =
        "(Reached the limit of $MAX_TOOL_ROUNDS tool rounds. Send “continue” to resume.)"

    /** 重复调用时的提醒(进协议、不进正文)。 */
    const val REPEAT_NUDGE_TEXT = REMINDER_PREFIX +
        "You called the same tools with the same arguments as before. Repeating them will not produce new information. " +
        "Change arguments, use other tools, or, if the task is complete, call " + COMPLETION_TOOL + " to finish. " +
        "If you can already answer the user's question, answer directly without replaying the same calls."

    /** 重复调用停下的可见提示。 */
    fun repeatStopNote(rounds: Int): String =
        "(Detected $rounds consecutive rounds of identical tool calls. Stopped; add new instructions or change the goal to continue.)"

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
     * @param repeatRounds 连续「本轮调用全部是重复调用」的轮数
     */
    fun decide(
        toolRounds: Int,
        toolCalls: Int,
        toolsEnabled: Boolean,
        nudgesUsed: Int,
        completed: Boolean = false,
        repeatRounds: Int = 0,
    ): Decision = when {
        completed -> Decision.Stop
        !toolsEnabled -> Decision.Stop
        // 原地打转:先提醒一次(给模型换路的机会),再重复就直接停 —— 不烧到轮次上限
        repeatRounds >= MAX_REPEAT_ROUNDS -> Decision.StopWithNote(repeatStopNote(repeatRounds))
        repeatRounds == 1 && nudgesUsed < MAX_CONTINUE_NUDGES -> Decision.Nudge(REPEAT_NUDGE_TEXT)
        toolRounds >= MAX_TOOL_ROUNDS -> Decision.StopWithNote(ROUND_LIMIT_NOTE)
        toolCalls > 0 -> Decision.Continue
        toolRounds == 0 -> Decision.Stop
        nudgesUsed < MAX_CONTINUE_NUDGES -> Decision.Nudge(NUDGE_TEXT)
        else -> Decision.Stop
    }
}
