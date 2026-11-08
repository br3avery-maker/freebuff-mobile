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
        if (example.isBlank()) description else description + "\nExample: " + example

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
            description = "Search the web: keywords → topic summary and related results. If none are found, try other keywords. " +
                "Use for facts, news, concepts, and current information. When the user provides a URL, use web_fetch directly. " +
                "For public repositories, files, and READMEs, use github_search_repositories / github_get_readme / github_get_file.",
            example = "web_search({\"query\": \"latest android compose version\"})",
            params = listOf(
                AgentTool.Param(
                    "query", "string", "Search keywords: 2–6 words separated by spaces, not a full question",
                    aliases = listOf("q", "keyword", "keywords", "关键词", "查询"),
                ),
            ),
        ),
        AgentTool(
            name = "web_fetch",
            description = "Fetch plain text from a URL, truncated to about 4000 characters. " +
                "Use directly when the user provides a link. Search first only when you need to find a link. " +
                "For files or READMEs in public GitHub repositories, use github_get_file / github_get_readme rather than constructing raw URLs.",
            example = "web_fetch({\"url\": \"https://example.com/post\"})",
            params = listOf(
                AgentTool.Param(
                    "url", "string", "Full URL starting with http:// or https://",
                    aliases = listOf("link", "href", "地址", "网址"),
                ),
            ),
        ),
        AgentTool(
            name = "github_search_repositories",
            description = "Search public GitHub repositories (name, stars, language, description). " +
                "Confirm the repository spelling before reading its files or README.",
            example = "github_search_repositories({\"query\": \"compose multiplatform\", \"limit\": 5})",
            params = listOf(
                AgentTool.Param(
                    "query", "string", "Search terms: repository name or technical keywords",
                    aliases = listOf("q", "keyword", "keywords", "搜索词"),
                ),
                AgentTool.Param(
                    "limit", "integer", "Number of results", required = false,
                    min = 1, max = 10, default = "5",
                ),
            ),
        ),
        AgentTool(
            name = "github_get_file",
            description = "Read a file in a public GitHub repository as plain text, truncated to about 4000 characters. " +
                "Supply owner and repo separately; path is relative to the repository. Use github_get_readme for READMEs. " +
                "Do not substitute web_fetch with a constructed raw URL.",
            example = "github_get_file({\"owner\": \"CodebuffAI\", \"repo\": \"freebuff\", \"path\": \"README.md\"})",
            params = listOf(
                AgentTool.Param(
                    "owner", "string", "Repository owner (account or organization), e.g. CodebuffAI",
                    aliases = listOf("user", "org", "organization", "账号"),
                ),
                AgentTool.Param(
                    "repo", "string", "Repository name, e.g. freebuff",
                    aliases = listOf("repository", "project", "仓库", "仓库名"),
                ),
                AgentTool.Param(
                    "path", "string", "Path within the repository, e.g. README.md or src/main.rs",
                    aliases = listOf("file", "filename", "filepath", "文件", "文件路径"),
                ),
                AgentTool.Param(
                    "ref", "string", "Branch or tag; omit to use the default branch", required = false,
                    aliases = listOf("branch", "tag", "版本"),
                ),
            ),
        ),
        AgentTool(
            name = "github_get_readme",
            description = "Read a public GitHub repository's README (truncated). " +
                "Start here when you know the repository but not its files. " +
                "Always use this for README or project-overview questions, even if the user provides a raw or webpage link.",
            example = "github_get_readme({\"owner\": \"CodebuffAI\", \"repo\": \"freebuff\"})",
            params = listOf(
                AgentTool.Param(
                    "owner", "string", "Repository owner (account or organization)",
                    aliases = listOf("user", "org", "organization", "账号"),
                ),
                AgentTool.Param(
                    "repo", "string", "Repository name",
                    aliases = listOf("repository", "project", "仓库", "仓库名"),
                ),
            ),
        ),
        AgentTool(
            name = "calculator",
            description = "Evaluate arithmetic exactly (+ - * / and parentheses). Use this for numerical calculations instead of mental arithmetic.",
            example = "calculator({\"expression\": \"(12+8)*3.5\"})",
            params = listOf(
                AgentTool.Param(
                    "expression", "string", "Expression, e.g. (12+8)*3.5",
                    aliases = listOf("expr", "formula", "算式", "表达式"),
                ),
            ),
        ),
        AgentTool(
            name = "current_time",
            description = "Get the device's current date and time, including weekday. Call this for “now” or “today”; do not guess from memory.",
            example = "current_time({})",
        ),
        AgentTool(
            name = "save_memory",
            description = "Update core memory blocks (persistent across chats). " +
                "Call when the user asks you to remember something or apply a future preference; do not only acknowledge it. " +
                "Save only information explicitly stated or confirmed by the user. " +
                "block must be persona (your identity), user (preferences and habits), or project (task focus and progress). " +
                "Replaces the whole block by default (replace=true); set replace=false to append.",
            example = "save_memory({\"block\": \"user\", \"content\": \"Preference: keep answers short\", \"replace\": false})",
            params = listOf(
                AgentTool.Param("block", "string", "Memory block name", enum = listOf("persona", "user", "project")),
                AgentTool.Param("content", "string", "Content to save: one concise statement, up to about 600 characters per block"),
                AgentTool.Param(
                    "replace", "boolean", "true replaces the block (default); false appends",
                    required = false, default = "true",
                ),
            ),
        ),
        AgentTool(
            name = Subagent.TOOL_NAME,
            description = "Assign a read-only subagent a self-contained task (web research, public repository reading, or calculations). It has its own context and tool loop and returns a result. " +
                "Use for independent research or reading, not a simple one-step call. task must be self-contained; the subagent cannot see this conversation.",
            example = "spawn_subagent({\"agent_type\": \"researcher\", \"task\": \"Find the main features in Compose 1.8\"})",
            params = listOf(
                AgentTool.Param(
                    "agent_type", "string", "Subagent type",
                    enum = listOf("researcher", "code_reader", "analyst"),
                ),
                AgentTool.Param("task", "string", "Self-contained subtask goal in one sentence (the subagent cannot see this conversation)"),
                AgentTool.Param("context", "string", "Minimal background needed from the main agent", required = false),
                AgentTool.Param("expected_output", "string", "Expected output, e.g. ≤200 words with a source list", required = false),
            ),
        ),
        AgentTool(
            name = AgentLoop.COMPLETION_TOOL,
            description = "Declare the task complete only when its goal is reached. summary must describe what was done, key results, and remaining work. " +
                "This ends the current loop. Do not call before a multi-step task is finished or after only describing a plan.",
            example = "task_completed({\"summary\": \"Checked the current time and reported it to the user; nothing remaining\"})",
            params = listOf(AgentTool.Param("summary", "string", "Completion summary: work done, key results, and remaining items")),
        ),
        AgentTool(
            name = "memory_recall",
            description = "Search historical memories (top K). Call before answering about preferences, prior agreements, task progress, or previously supplied facts. " +
                "If nothing is found, you may ask the user. This tool reads; save_memory writes. Do not substitute one for the other. " +
                "Use the current user ID from the system prompt for user_id.",
            example = "memory_recall({\"user_id\": \"local\", \"query\": \"preferred answer length\"})",
            params = listOf(
                AgentTool.Param("user_id", "string", "User identifier: current user_id from the system prompt"),
                AgentTool.Param(
                    "query", "string", "Question or search terms to answer now",
                    aliases = listOf("q", "keyword", "查询", "关键词"),
                ),
                AgentTool.Param(
                    "top_k", "integer", "Number of results", required = false,
                    min = 1, max = 20, default = "5",
                ),
                AgentTool.Param(
                    "memory_type", "string", "Type filter: long_term for preferences/facts, short_term for task progress",
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
                        problems += ToolErrors.ArgProblem.Type(p.name, raw.toString().take(40), "integer")
                    } else if ((p.min != null && n < p.min!!) || (p.max != null && n > p.max!!)) {
                        problems += ToolErrors.ArgProblem.Range(p.name, n.toString(), p.min, p.max)
                    }
                }
                "boolean" -> if (asBool(raw) == null) {
                    problems += ToolErrors.ArgProblem.Type(p.name, raw.toString().take(40), "boolean (true/false)")
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
