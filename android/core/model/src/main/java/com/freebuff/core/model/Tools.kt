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
 * 设计对应 agent-architecture.md §1 的注册表思想:工具 = 名称 + 说明书 + 参数 Schema。
 * [toJsonObject] 产出 OpenAI 兼容 `tools` 字段;执行端(core/data ToolExecutors)按 name 分派。
 *
 * 说明书怎么写(来自 Anthropic《Writing effective tools for agents》与 MCP 工具设计规范,弱模型尤其吃这一套):
 * 1. **描述是给模型看的提示词**:做什么 → 何时用 → 何时**不用**(改用哪个)→ 返回什么 → 一句调用示例;
 * 2. **闭集用 enum**:能枚举的取值一律列出来,模型便从列表里挑而不是编(如 block 只能 persona/user/project);
 * 3. **数值给上下界**:limit/top_k 这类参数声明 min/max,模型不必猜;
 * 4. **只把「工具不能自己推断」的参数标为必填**:其余给默认值,减少模型编造;
 * 5. **参数名要能自解释**,同时容忍常见别名(弱模型常把 path 写成 file、query 写成 q);
 * 6. 说明书与 schema 每轮都随请求注入(见 [DefaultTools.toJsonArrayString]),不依赖模型记忆。
 */
data class AgentTool(
    val name: String,
    /** 说明书:做什么 / 何时用、何时不用 / 返回什么(例见 [example])。 */
    val description: String,
    /** 参数 schema:参数名 → (类型, 描述, 是否必填, 闭集/上下界/默认值/别名)。 */
    val params: List<Param> = emptyList(),
    /** 一句正确调用示例:写进 schema 描述与错误信封 —— 示例是说明书里最有价值的一行。 */
    val example: String = "",
) {
    data class Param(
        val name: String,
        val type: String,
        val desc: String,
        val required: Boolean = true,
        /** 闭集取值(schema 的 enum;端上也会校验一次)。 */
        val enum: List<String> = emptyList(),
        /** 数值上下界(schema 的 minimum / maximum)。 */
        val min: Int? = null,
        val max: Int? = null,
        /** 默认值(写进 schema;不传时执行器用同一默认值)。 */
        val default: String = "",
        /** 常见别名:一并接受并归一到规范名(降低弱模型的参数名门槛)。 */
        val aliases: List<String> = emptyList(),
    ) {
        fun jsonSchema(): JSONObject = JSONObject().apply {
            put("type", type).put("description", desc)
            if (enum.isNotEmpty()) put("enum", JSONArray().apply { enum.forEach { put(it) } })
            min?.let { put("minimum", it) }
            max?.let { put("maximum", it) }
            if (default.isNotBlank()) put("default", default)
        }
    }

    /** 注入给模型的 description:说明书 + 示例一行(没有示例时就是说明书本身)。 */
    fun schemaDescription(): String =
        if (example.isBlank()) description else description + "\n例: " + example

    /** OpenAI function calling 的单个工具定义。 */
    fun toJsonObject(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", schemaDescription())
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject().apply {
                            params.forEach { p -> put(p.name, p.jsonSchema()) }
                        })
                        .put("required", JSONArray().apply { params.filter { it.required }.forEach { put(it.name) } }),
                ),
        )
}

/**
 * 默认工具集。名称与展示标签(toolDisplayName)对齐,未知工具回退原名显示。
 *
 * 除了注册表本身,这里还提供**执行前的参数体检**([validate] / [normalize]):
 * 不做体检时,弱模型把 `path` 写成 `file` 或把 limit 写成 `"很多"` 会一路走到执行器深处,
 * 报出一句没头没尾的错;体检后回给模型的是「哪个参数、错在哪、正确名字是什么」。
 */
object DefaultTools {

    val ALL: List<AgentTool> = listOf(
        AgentTool(
            name = "web_search",
            description = "联网搜索:关键词 → 主题摘要 + 相关条目(没搜到时会提示换词)。" +
                "适合查事实、新闻、概念、最新动态。用户已经给出具体网址时不要用它,直接 web_fetch;" +
                "要找公开仓库或读仓库里的文件、README 时,改用 github_search_repositories / github_get_readme / github_get_file。",
            example = "web_search({\"query\": \"android compose 最新版本\"})",
            params = listOf(
                AgentTool.Param(
                    "query", "string", "搜索关键词:2~6 个词,用空格分隔,不要整句提问",
                    aliases = listOf("q", "keyword", "keywords", "关键词", "查询"),
                ),
            ),
        ),
        AgentTool(
            name = "web_fetch",
            description = "抓取指定网址的正文(纯文本,截断到约 4000 字)。" +
                "用户已经给出链接时直接用它,不要先搜索一遍;要先找链接才用 web_search。" +
                "目标若是 GitHub 公开仓库里的文件或 README,改用 github_get_file / github_get_readme,不要自己拼 raw.githubusercontent.com 之类的链接。",
            example = "web_fetch({\"url\": \"https://example.com/post\"})",
            params = listOf(
                AgentTool.Param(
                    "url", "string", "完整网址,必须以 http:// 或 https:// 开头",
                    aliases = listOf("link", "href", "地址", "网址"),
                ),
            ),
        ),
        AgentTool(
            name = "github_search_repositories",
            description = "在 GitHub 搜公开仓库(名称/星数/语言/简介)。" +
                "读某个仓库的文件或 README 之前,先用它确认仓库名拼写正确。",
            example = "github_search_repositories({\"query\": \"compose multiplatform\", \"limit\": 5})",
            params = listOf(
                AgentTool.Param(
                    "query", "string", "搜索词:仓库名或技术关键词",
                    aliases = listOf("q", "keyword", "keywords", "搜索词"),
                ),
                AgentTool.Param(
                    "limit", "integer", "返回条数", required = false,
                    min = 1, max = 10, default = "5",
                ),
            ),
        ),
        AgentTool(
            name = "github_get_file",
            description = "读 GitHub 公开仓库里的某个文件(纯文本,截断到约 4000 字)。" +
                "owner 与 repo 要分开写,path 是仓库内的相对路径;只想读 README 时用 github_get_readme。" +
                "不要用 web_fetch 拼 raw 链接代替它。",
            example = "github_get_file({\"owner\": \"CodebuffAI\", \"repo\": \"freebuff\", \"path\": \"README.md\"})",
            params = listOf(
                AgentTool.Param(
                    "owner", "string", "仓库所有者(账号或组织名),如 CodebuffAI",
                    aliases = listOf("user", "org", "organization", "账号"),
                ),
                AgentTool.Param(
                    "repo", "string", "仓库名,如 freebuff",
                    aliases = listOf("repository", "project", "仓库", "仓库名"),
                ),
                AgentTool.Param(
                    "path", "string", "仓库内文件路径,如 README.md 或 src/main.rs",
                    aliases = listOf("file", "filename", "filepath", "文件", "文件路径"),
                ),
                AgentTool.Param(
                    "ref", "string", "分支/标签名;不填用默认分支", required = false,
                    aliases = listOf("branch", "tag", "版本"),
                ),
            ),
        ),
        AgentTool(
            name = "github_get_readme",
            description = "读 GitHub 公开仓库的 README 全文(截断)。" +
                "知道仓库但不确定里面有哪些文件时,先用它了解项目;" +
                "用户问某个仓库的 README 或项目介绍时一律用它,即使对方给了 raw 链接或网页链接,也不要用 web_fetch 代替。",
            example = "github_get_readme({\"owner\": \"CodebuffAI\", \"repo\": \"freebuff\"})",
            params = listOf(
                AgentTool.Param(
                    "owner", "string", "仓库所有者(账号或组织名)",
                    aliases = listOf("user", "org", "organization", "账号"),
                ),
                AgentTool.Param(
                    "repo", "string", "仓库名",
                    aliases = listOf("repository", "project", "仓库", "仓库名"),
                ),
            ),
        ),
        AgentTool(
            name = "calculator",
            description = "精确计算算术表达式(支持 + - * / 与括号)。数字计算一律用它,不要自己心算。",
            example = "calculator({\"expression\": \"(12+8)*3.5\"})",
            params = listOf(
                AgentTool.Param(
                    "expression", "string", "算式,如 (12+8)*3.5",
                    aliases = listOf("expr", "formula", "算式", "表达式"),
                ),
            ),
        ),
        AgentTool(
            name = "current_time",
            description = "取设备当前的日期与时间(含星期)。问到「现在/今天」时先调它,不要凭记忆猜日期。",
            example = "current_time({})",
        ),
        AgentTool(
            name = "save_memory",
            description = "更新核心记忆块(跨会话长期生效)。" +
                "用户说「记住…」「以后都…」「别忘…」时必须调用它(这一步最容易漏),不要只在回复里答应。" +
                "只记用户明确说出或确认过的信息。" +
                "block 只能取 persona(你的身份)/user(用户的偏好与习惯)/project(当前任务的焦点与进展);" +
                "默认整块覆盖(replace=true),想追加就 replace=false。",
            example = "save_memory({\"block\": \"user\", \"content\": \"偏好:回答尽量短\", \"replace\": false})",
            params = listOf(
                AgentTool.Param("block", "string", "记忆块名", enum = listOf("persona", "user", "project")),
                AgentTool.Param("content", "string", "要保存的内容:精炼成一句陈述,单块上限约 600 字"),
                AgentTool.Param(
                    "replace", "boolean", "true=覆盖整块(默认),false=追加到现有内容后",
                    required = false, default = "true",
                ),
            ),
        ),
        AgentTool(
            name = Subagent.TOOL_NAME,
            description = "派一个只读子代理独立完成子任务(联网调研/读公开仓库/纯计算),它有独立上下文与工具循环,只回结论。" +
                "适合可并行、自成一体的调研或读取;简单的一步调用别用它。task 必须自包含(它看不到当前对话)。",
            example = "spawn_subagent({\"agent_type\": \"researcher\", \"task\": \"查 compose 1.8 的主要新特性\"})",
            params = listOf(
                AgentTool.Param(
                    "agent_type", "string", "子代理类型",
                    enum = listOf("researcher", "code_reader", "analyst"),
                ),
                AgentTool.Param("task", "string", "子任务目标:一句话、自包含(子代理看不到当前对话)"),
                AgentTool.Param("context", "string", "主 agent 认为必要的最小背景摘录", required = false),
                AgentTool.Param("expected_output", "string", "期望产出形态,如 ≤200 字结论 + 来源列表", required = false),
            ),
        ),
        AgentTool(
            name = AgentLoop.COMPLETION_TOOL,
            description = "声明任务完成:确认目标真正达成后才调用,summary 写清做了什么、关键结果与遗留事项。" +
                "调用后本次循环结束。多步任务在完成前不要用它;也不要只是描述了计划就停。",
            example = "task_completed({\"summary\": \"已查出当前时间并汇报给用户;无遗留\"})",
            params = listOf(AgentTool.Param("summary", "string", "完成情况:做了什么 + 关键结果 + 遗留事项")),
        ),
        AgentTool(
            name = "memory_recall",
            description = "按查询检索历史记忆(Top K 条)。回答涉及用户偏好、过往约定、任务进度、以前提供过的事实时,先调它;" +
                "没检索到也可以再问用户。它负责读,save_memory 负责写,别互相替代。" +
                "user_id 填系统提示里给出的当前用户 id。",
            example = "memory_recall({\"user_id\": \"local\", \"query\": \"回答长度偏好\"})",
            params = listOf(
                AgentTool.Param("user_id", "string", "用户标识:填系统提示里的当前 user_id"),
                AgentTool.Param(
                    "query", "string", "当前要回答的问题或查询词",
                    aliases = listOf("q", "keyword", "查询", "关键词"),
                ),
                AgentTool.Param(
                    "top_k", "integer", "检索条数", required = false,
                    min = 1, max = 20, default = "5",
                ),
                AgentTool.Param(
                    "memory_type", "string", "类型过滤:长期=偏好/事实,短期=任务进度",
                    required = false, enum = listOf("long_term", "short_term"),
                ),
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

    /** 按名字查工具(校验与错误信封要给模型参数清单/示例,都需要它)。 */
    fun find(name: String, tools: List<AgentTool> = ALL): AgentTool? = tools.firstOrNull { it.name == name }

    /** 当前可用工具名清单(未知工具的错误信封里原样列出,模型据此改对)。 */
    fun names(tools: List<AgentTool> = ALL): List<String> = tools.map { it.name }

    /**
     * 该名字是不是**当前可用**的工具。
     *
     * 为什么要单独问一句:模型会凭空造工具名(实测 `browse_web`)。未知名字根本执行不了,
     * 却会落到权限层的未知兜底(CONFIRM)—— 用户被弹窗拦一道,点「允许」之后拿到的仍然是
     * 「未知工具」,纯噪声且会卡住循环。所以未知名字在执行入口直接回错误结果,不弹确认。
     * 传 tools 而不是只看 ALL:关掉记忆能力时,save_memory 也不该被认作可用。
     */
    fun isKnown(name: String, tools: List<AgentTool> = ALL): Boolean = find(name, tools) != null

    /**
     * 执行前的参数体检。返回空表表示可以执行。
     *
     * 顺序有意为之:先报**未知参数名**(多半是名字写错,改对后其它问题往往一起消失),
     * 再报必填缺失,最后才是取值问题 —— 弱模型一次只改得动一件事。
     */
    fun validate(tool: AgentTool, args: JSONObject): List<ToolErrors.ArgProblem> {
        val problems = mutableListOf<ToolErrors.ArgProblem>()
        args.keys().forEach { k ->
            if (canonical(tool, k) == null) problems += ToolErrors.ArgProblem.Unknown(k)
        }
        tool.params.forEach { p ->
            val raw = valueFor(tool, args, p)
            if (isAbsent(raw)) {
                if (p.required && p.default.isBlank()) problems += ToolErrors.ArgProblem.Missing(p.name)
                return@forEach
            }
            when (p.type) {
                "integer" -> {
                    val n = asInt(raw)
                    if (n == null) {
                        problems += ToolErrors.ArgProblem.Type(p.name, raw.toString().take(40), "整数")
                    } else if ((p.min != null && n < p.min!!) || (p.max != null && n > p.max!!)) {
                        problems += ToolErrors.ArgProblem.Range(p.name, n.toString(), p.min, p.max)
                    }
                }
                "boolean" -> if (asBool(raw) == null) {
                    problems += ToolErrors.ArgProblem.Type(p.name, raw.toString().take(40), "布尔(true/false)")
                }
                else -> {
                    if (p.enum.isNotEmpty() && p.enum.none { it.equals(raw.toString().trim(), ignoreCase = true) }) {
                        problems += ToolErrors.ArgProblem.Enum(p.name, raw.toString().trim().take(40), p.enum)
                    }
                }
            }
        }
        return problems
    }

    /**
     * 参数归一:别名 → 规范名、字符串数字 → 整数、"True"/"1" → 布尔、枚举大小写对齐,
     * 丢掉没给值的可选参数(执行器用默认值)。
     * 归一与校验分开:归一的结果同时给执行器和信封用,信封里的「正确调用示例」才对得上实际行为。
     */
    fun normalize(tool: AgentTool, args: JSONObject): JSONObject {
        val out = JSONObject()
        tool.params.forEach { p ->
            val raw = valueFor(tool, args, p)
            if (isAbsent(raw)) return@forEach
            when (p.type) {
                "integer" -> asInt(raw)?.let { out.put(p.name, it) }
                "boolean" -> asBool(raw)?.let { out.put(p.name, it) }
                else -> {
                    val s = raw as? String ?: raw.toString()
                    val trimmed = s.trim()
                    val canon = p.enum.firstOrNull { it.equals(trimmed, ignoreCase = true) }
                    out.put(p.name, canon ?: trimmed)
                }
            }
        }
        return out
    }

    /** 校验 + 归一一步到位(执行器入口用):problems 非空就别执行,把信封回给模型。 */
    fun prepare(tool: AgentTool, args: JSONObject): Pair<JSONObject, List<ToolErrors.ArgProblem>> =
        normalize(tool, args) to validate(tool, args)

    /* ---------------- 内部工具 ---------------- */

    /** 规范名优先,别名(忽略大小写)兜底;都没命中返回 null。 */
    private fun canonical(tool: AgentTool, key: String): AgentTool.Param? =
        tool.params.firstOrNull { it.name == key }
            ?: tool.params.firstOrNull { p -> p.aliases.any { it.equals(key, ignoreCase = true) } }

    /** 取参数值:规范名优先;缺了就用别名找。 */
    private fun valueFor(tool: AgentTool, args: JSONObject, p: AgentTool.Param): Any? {
        if (args.has(p.name)) return args.opt(p.name)
        p.aliases.forEach { a ->
            val hit = args.keys().asSequence().firstOrNull { it.equals(a, ignoreCase = true) }
            if (hit != null) return args.opt(hit)
        }
        return null
    }

    private fun isAbsent(v: Any?): Boolean =
        v == null || JSONObject.NULL == v || (v is String && v.isBlank())

    /** 宽松取整:数字或数字字符串都收(弱模型常把 5 写成 "5")。 */
    private fun asInt(v: Any?): Int? {
        val d = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        } ?: return null
        if (d.isNaN() || d.isInfinite() || d != Math.floor(d) || Math.abs(d) > Int.MAX_VALUE) return null
        return d.toInt()
    }

    /** 宽松取布尔:true/false、"true"/"false"、1/0、是/否。 */
    private fun asBool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> if (v.toInt() == 1) true else if (v.toInt() == 0) false else null
        is String -> when (v.trim().lowercase()) {
            "true", "1", "yes", "是" -> true
            "false", "0", "no", "否" -> false
            else -> null
        }
        else -> null
    }
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
