package com.freebuff.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 端侧工具注册表:让对话模型通过 function calling 调用 App 本地能力。
 *
 * 工具全部来自开源生态的公开协议,App 端真实执行(无需自建后端):
 * - GitHub REST API(github_search_repositories / github_get_file / github_get_readme)—— 匿名可读公开数据
 * - DuckDuckGo Instant Answer API(web_search)—— 免钥搜索
 * - 通用网页抓取(web_fetch)、本地计算(calculator)、本地时间(current_time)
 *
 * 设计对应 agent-architecture.md §1 的注册表思想:工具 = 名称 + 描述 + 参数 Schema。
 * [toJsonArray] 产出 OpenAI 兼容 `tools` 字段;执行端(core/data ToolExecutors)按 name 分派。
 */
data class AgentTool(
    val name: String,
    val description: String,
    /** 参数 schema:参数名 → (类型, 描述, 是否必填)。 */
    val params: List<Param> = emptyList(),
) {
    data class Param(val name: String, val type: String, val desc: String, val required: Boolean = true)

    /** OpenAI function calling 的单个工具定义。 */
    fun toJsonObject(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject().apply {
                            params.forEach { p -> put(p.name, JSONObject().put("type", p.type).put("description", p.desc)) }
                        })
                        .put("required", JSONArray().apply { params.filter { it.required }.forEach { put(it.name) } }),
                ),
        )
}

/**
 * 默认工具集。名称与展示标签(toolDisplayName)对齐,未知工具回退原名显示。
 */
object DefaultTools {

    val ALL: List<AgentTool> = listOf(
        AgentTool(
            name = "web_search",
            description = "联网搜索:按关键词查询,返回主题摘要与相关条目。适合查事实、新闻、概念解释。",
            params = listOf(AgentTool.Param("query", "string", "搜索关键词,尽量精炼")),
        ),
        AgentTool(
            name = "web_fetch",
            description = "抓取网页正文:返回该 URL 页面的纯文本内容(截断)。适合在已知具体链接时读取详情。",
            params = listOf(AgentTool.Param("url", "string", "完整的 http(s) 链接")),
        ),
        AgentTool(
            name = "github_search_repositories",
            description = "在 GitHub 搜索开源仓库:按关键词返回仓库名、星数、语言与简介。",
            params = listOf(
                AgentTool.Param("query", "string", "搜索词,如 android compose"),
                AgentTool.Param("limit", "integer", "返回条数,默认 5,最大 10", required = false),
            ),
        ),
        AgentTool(
            name = "github_get_file",
            description = "读取 GitHub 公开仓库的文件内容(文本,截断)。owner/repo 需拆开填写。",
            params = listOf(
                AgentTool.Param("owner", "string", "仓库所有者,如 CodebuffAI"),
                AgentTool.Param("repo", "string", "仓库名,如 freebuff"),
                AgentTool.Param("path", "string", "文件路径,如 README.md 或 src/main.rs"),
                AgentTool.Param("ref", "string", "分支/标签,默认仓库默认分支", required = false),
            ),
        ),
        AgentTool(
            name = "github_get_readme",
            description = "读取 GitHub 公开仓库的 README 全文(截断)。",
            params = listOf(
                AgentTool.Param("owner", "string", "仓库所有者"),
                AgentTool.Param("repo", "string", "仓库名"),
            ),
        ),
        AgentTool(
            name = "calculator",
            description = "计算算术表达式:支持 + - * / 与括号。适合需要精确数值的场景。",
            params = listOf(AgentTool.Param("expression", "string", "算式,如 (12+8)*3.5")),
        ),
        AgentTool(
            name = "current_time",
            description = "获取设备当前日期与时间。",
            params = emptyList(),
        ),
        AgentTool(
            name = "save_memory",
            description = "更新你的核心记忆块(Letta 式):block 取 persona(你的身份)/user(关于用户的偏好与习惯)/project(当前任务焦点与进展)。content 为新的块内容;replace=false 时追加到现有内容后。适合记住用户偏好、长期任务上下文等跨会话信息。",
            params = listOf(
                AgentTool.Param("block", "string", "记忆块名:persona / user / project"),
                AgentTool.Param("content", "string", "要保存的内容(精炼,单块上限约 600 字)"),
                AgentTool.Param("replace", "boolean", "true=覆盖块内容(默认),false=追加", required = false),
            ),
        ),
        AgentTool(
            name = Subagent.TOOL_NAME,
            description = "派出一个只读子代理去完成独立子任务(联网调研/读公开仓库/纯计算),它自己拥有独立上下文与工具循环,最终只返回结论。适合把可并行的调研、读取类工作委托出去,自己继续主线。子任务描述必须自包含——子代理看不到当前对话。",
            params = listOf(
                AgentTool.Param("agent_type", "string", "子代理类型:researcher(联网调研)/ code_reader(读公开仓库文件)/ analyst(纯计算)"),
                AgentTool.Param("task", "string", "子任务目标,一句话、自包含(子代理看不到当前对话)"),
                AgentTool.Param("context", "string", "主 agent 认为必要的最小背景摘录", required = false),
                AgentTool.Param("expected_output", "string", "期望产出的形态,如 ≤200 字结论+来源列表", required = false),
            ),
        ),
        AgentTool(
            name = AgentLoop.COMPLETION_TOOL,
            description = "声明任务完成:确认目标真正达成后才调用,summary 写清做了什么、关键结果与遗留事项。" +
                "调用后本次循环结束。多步任务在完成前不要用它;也不要只是描述计划就停。",
            params = listOf(AgentTool.Param("summary", "string", "完成情况:做了什么 + 关键结果 + 遗留事项")),
        ),
        AgentTool(
            name = "memory_recall",
            description = "检索记忆库:按 user_id 与 query 返回最相关的历史记忆条目(Top K,默认 5)。适合在回答涉及用户偏好、过往约定、任务进度、以前提供过的事实之前调用;memory_type 可只看长期(偏好/事实)或短期(任务进度)。",
            params = listOf(
                AgentTool.Param("user_id", "string", "用户标识,用于隔离不同用户的记忆(必填)"),
                AgentTool.Param("query", "string", "当前需要响应的用户输入或查询词"),
                AgentTool.Param("top_k", "integer", "检索条数,默认 5,最大 20", required = false),
                AgentTool.Param("memory_type", "string", "类型过滤:long_term(长期偏好/事实)/ short_term(任务进度)", required = false),
            ),
        ),
    )

    /** 记忆能力相关工具:设置页「上下文记忆」关闭时整体移除(模型不应看到不可用的工具)。 */
    private val MEMORY_TOOLS = setOf("save_memory", "memory_recall")

    /** 序列化为 OpenAI 兼容 `tools` 数组字符串(直接放进请求体)。 */
    fun toJsonArrayString(tools: List<AgentTool> = ALL): String =
        JSONArray().apply { tools.forEach { put(it.toJsonObject()) } }.toString()

    /** 按开关过滤的工具集:记忆关闭时移除记忆类工具(save_memory / memory_recall)。 */
    fun forCapabilities(memory: Boolean): List<AgentTool> =
        if (memory) ALL else ALL.filter { it.name !in MEMORY_TOOLS }
}

/** assistant 发起的一笔工具调用(循环回传时组装 assistant 消息用)。 */
data class ToolCallReq(
    val callId: String,
    val name: String,
    /** 原始 arguments JSON 字符串(执行端解析;空视为 {})。 */
    val argsJson: String,
)

/** 执行结果。isError 时模型会收到错误文案并自行决定重试或致歉。 */
data class ToolOutcome(val content: String, val isError: Boolean = false)
