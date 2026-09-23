package com.freebuff.core.model

/* ---------------- 数据模型(与原型一一对应) ---------------- */

/** Git 账号连接状态。connected=false 时其余字段为空占位。 */
data class GitState(
    val connected: Boolean = false,
    val provider: String = "",
    val login: String = "",
    val name: String = "",
)

/** 连接探测结果:at 为探测时刻,ms 为往返耗时。 */
data class Probe(
    val at: Long = 0L,
    val ms: Int = 0,
)

/**
 * 用户自定义模型配置。对应 custom-model-spec.md 的字段模型。
 * - key: API Key,落库前由 CryptoManager 加密
 * - headers: 附加请求头,JSON 字符串
 * - models: 最近一次 /v1/models 拉取的模型快照
 * - probe: 最近测试连接结果(按模型 id)
 */
data class CustomModel(
    val id: String = "",
    val name: String = "",
    val apiId: String = "",
    val base: String = "",
    val key: String = "",
    val ctx: String = "",
    val timeout: String = "",
    val headers: String = "",
    val skipTLS: Boolean = false,
    val models: List<String> = emptyList(),
    val probe: Map<String, Probe> = emptyMap(),
)

/** 官方(网关)模型目录条目。tier: full | limited | trial | custom */
data class OfficialModel(
    val id: String,
    val name: String,
    val badge: String,
    val tier: String,
    val tierText: String,
    val desc: String,
)

/** 对话消息中 agent 的阶段性步骤(如"规划"/"执行")。 */
data class MsgStep(val name: String, val sub: String)

/** 单条对话消息。role: user | agent。tools 为消息内嵌的工具卡片(agent 消息专用)。 */
data class ChatMsg(
    val id: String,
    val role: String,
    val text: String = "",
    val time: String = "",
    val steps: List<MsgStep> = emptyList(),
    val md: String = "",
    val codeLang: String = "",
    val code: String = "",
    val md2: String = "",
    val ctxRepo: String = "",
    val ctxModel: String = "",
    val tools: List<ToolCard> = emptyList(),
)

/** 会话。messages 为会话内消息(内存中完整持有,持久化见 core:data Room)。 */
data class Session(
    val id: String,
    val title: String,
    val time: String,
    val preview: String,
    val messages: List<ChatMsg>,
)

data class RepoItem(val name: String, val branch: String, val desc: String)

/** 发起任务三步向导的草稿状态。 */
data class TaskDraft(
    val repoMode: String = "none", // none | manual | git
    val repoUrl: String = "",
    val repoName: String = "",
    val repoBranch: String = "",
    val modelId: String = "deepseek-v4-flash",
    val desc: String = "",
    val step: Int = 0,
)

/** 迁移清洗工具:RepairReport 描述修复了多少条损坏的自定义模型记录。 */
data class RepairFix(val code: String, val n: Int)
data class RepairItem(val i: Int, val fixes: List<RepairFix>, val raw: Any?, val fixed: Any?)
data class RepairReport(
    val fixed: Int = 0,
    val items: List<RepairItem> = emptyList(),
    val rawN: Int = 0,
    val nowN: Int = 0,
    val raw: List<Any?>? = null,
)

/** 版本检查:小于当前版本号视为有更新。 */
const val LATEST_VERSION = "0.0.1"

/** 一次对话的完整请求目标(由模型解析而来,官方/自定义统一)。 */
data class ChatTarget(
    val endpoint: String,
    val model: String,
    val apiKey: String = "",
    val headers: Map<String, String> = emptyMap(),
    val skipTLS: Boolean = false,
    val name: String = "",
    /** 上下文窗口声明("128k"/"1m"/"200000"),空串用默认保守值。 */
    val ctxWindow: String = "",
) {
    val isConfigured: Boolean get() = endpoint.isNotBlank()
}

/** 远程版本检查结果。 */
data class RemoteVersion(
    val version: String = "",
    val notes: List<String> = emptyList(),
    /** 新版 APK 下载页(可选)。update.json 带 url 时,更新面板展示「前往下载」。 */
    val url: String = "",
) {
    fun isNewerThan(current: String): Boolean {
        val v = version.removePrefix("v")
        val c = current.removePrefix("v")
        return v != c && compareVersions(v, c) > 0
    }
}

private fun compareVersions(a: String, b: String): Int {
    val pa = a.split(".").mapNotNull { it.toIntOrNull() }
    val pb = b.split(".").mapNotNull { it.toIntOrNull() }
    val n = maxOf(pa.size, pb.size)
    for (i in 0 until n) {
        val x = pa.getOrElse(i) { 0 }
        val y = pb.getOrElse(i) { 0 }
        if (x != y) return x - y
    }
    return 0
}

/**
 * 将自定义模型映射为统一的模型目录条目(ModelSheet / 任务向导共用)。
 * tier 固定为 "custom",desc 展示端点。
 */
fun List<CustomModel>.asOfficialModels(): List<OfficialModel> = map { m ->
    OfficialModel(
        id = m.id,
        name = m.name,
        badge = "M",
        tier = "custom",
        tierText = "自定义模型",
        desc = m.base.ifBlank { "自定义端点" },
    )
}

/**
 * 模型目录合并:官方 + 自定义,按官方在前、自定义在后的顺序。
 * @param official 官方目录(网关实时或内置回退)
 */
fun mergedModelList(official: List<OfficialModel>, customs: List<CustomModel>): List<OfficialModel> =
    official + customs.asOfficialModels()

/** 兼容重载:使用内置官方目录。 */
fun mergedModelList(customs: List<CustomModel>): List<OfficialModel> =
    mergedModelList(builtinOfficialModels, customs)
