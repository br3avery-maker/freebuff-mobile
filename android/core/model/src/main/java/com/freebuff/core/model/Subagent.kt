package com.freebuff.core.model

import org.json.JSONObject

/**
 * 端侧子代理协议层(spawn_subagent,见 docs/subagent-design.md):
 * - 编排工具定义与参数解析(主模型 → 端侧)
 * - 三种内置只读剧本(agent_type → system prompt / 工具白名单 / 输出预算)
 * - 深度固定 1:子代理工具集不含 spawn_subagent;CONFIRM 级工具在子代理内不可用
 *
 * 纯函数层,无协程/Android 依赖;单测见 SubagentTest。
 */
object Subagent {

    /** 编排工具名(主循环据此分派)。 */
    const val TOOL_NAME = "spawn_subagent"

    /** 单轮最多派出的子代理数(超出部分立即 error 回填,不排队)。 */
    const val MAX_PER_ROUND = 3

    /** 并发上限(P0 串行,P1 用;常量先行)。 */
    const val MAX_CONCURRENT = 2

    /** 单个子代理的总时限(ms),含其全部轮次与工具调用。 */
    const val DEADLINE_MS = 120_000L

    /** 子代理流式空闲看门狗阈值:比主循环(120s)更激进。 */
    const val IDLE_TIMEOUT_MS = 60_000L

    /** 子代理内 LLM 调用失败的最大重试次数(比主循环 2 次更保守)。 */
    const val MAX_RETRIES = 1

    /** 子代理循环内工具执行的最大轮数。 */
    const val MAX_TOOL_ROUNDS = 3

    /** 子代理类型:剧本名。 */
    const val TYPE_RESEARCHER = "researcher"
    const val TYPE_CODE_READER = "code_reader"
    const val TYPE_ANALYST = "analyst"

    /** 各类型的工具白名单(只读;不含 spawn_subagent —— 深度固定 1)。 */
    val TOOL_WHITELIST: Map<String, Set<String>> = mapOf(
        TYPE_RESEARCHER to setOf("web_search", "web_fetch"),
        TYPE_CODE_READER to setOf("github_get_file", "github_get_readme"),
        TYPE_ANALYST to setOf("calculator"),
    )

    /** agent_type 是否有效。 */
    fun isValidType(type: String): Boolean = TOOL_WHITELIST.containsKey(type)

    /**
     * 解析 spawn_subagent 的 arguments JSON。
     * @return 成功 → [Request];失败 → null(非法 agent_type/空 task 都算失败,调用方 error 回填)
     */
    fun parseRequest(argsJson: String): Request? {
        return try {
            val o = JSONObject(argsJson)
            val type = o.optString("agent_type").trim()
            val task = o.optString("task").trim()
            if (!isValidType(type) || task.isEmpty()) return null
            Request(
                type = type,
                task = task,
                context = o.optString("context").trim(),
                expectedOutput = o.optString("expected_output").trim(),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 一次子代理委托(解析后的参数)。 */
    data class Request(
        val type: String,
        val task: String,
        val context: String,
        val expectedOutput: String,
    )

    /** 子代理的 system 剧本:身份目标 + 工作方式 + 硬性约束 + 输出格式四段。 */
    fun systemPrompt(type: String): String {
        val tools = TOOL_WHITELIST[type].orEmpty().joinToString(", ")
        return buildString {
            appendLine("You are a subagent assigned by the main agent. Your type is $type. Complete only the delegated subtask; do not talk to the user directly.")
            appendLine("Available tools: $tools. Use a few focused tool calls at a time, up to $MAX_TOOL_ROUNDS rounds. Do not explore outside the subtask.")
            appendLine("Constraints: use only the listed tools; do not fabricate data or sources; report tool failures honestly without claiming success.")
            appendLine("Output a concise, self-contained result with bullet points. The reader cannot see your tool calls. Do not add greetings or repeat the task.")
        }.trim()
    }

    /** 子代理的首条 user 消息:任务 + 最小背景 + 期望产出形态。 */
    fun userPrompt(req: Request): String = buildString {
        append("Subtask: ").append(req.task)
        if (req.context.isNotBlank()) append("\nBackground (minimal context from the main agent): ").append(req.context.take(2000))
        if (req.expectedOutput.isNotBlank()) append("\nExpected output: ").append(req.expectedOutput)
    }

    /** 按类型取子代理可用的工具定义(只读白名单硬过滤;未知类型返回空)。 */
    fun toolsFor(type: String): List<AgentTool> {
        val allow = TOOL_WHITELIST[type] ?: return emptyList()
        return DefaultTools.ALL.filter { it.name in allow }
    }

    /**
     * 占位分派回填(P0 收敛为协议层,执行引擎未上线):主循环识别到 spawn_subagent 调用时
     * 不执行、不弹权限确认,直接回填本说明 —— 无副作用,同时让模型提前熟悉该工具的调用形态,
     * P1 执行引擎上线后无缝切换,无需改提示词。
     */
    fun comingSoonMessage(): String =
        "spawn_subagent is not available yet; this call did not execute. Complete this subtask directly using the current context. " +
            "For external information, use web_search / web_fetch step by step."

    /** spawn_subagent 参数缺失/非法时给主模型的可修正错误文案。 */
    fun invalidArgsMessage(argsJson: String): String =
        "Invalid spawn_subagent arguments (requires agent_type=$TYPE_RESEARCHER|$TYPE_CODE_READER|$TYPE_ANALYST and a non-empty task). Received: ${argsJson.take(200)}"
}
