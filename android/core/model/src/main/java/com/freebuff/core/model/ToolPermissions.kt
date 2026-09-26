package com.freebuff.core.model

import org.json.JSONObject

/**
 * 端侧工具权限分级(「权限由工具集强制,不靠提示词自觉」思想):
 * - [ALLOW]:免确认,静默执行(只读/无副作用工具)
 * - [CONFIRM]:每次执行前弹确认框,用户批准才执行
 * - [DENY]:直接拒绝执行,回传错误给模型(模型可换路或致歉)
 *
 * 权限映射持久化在 settings 表(键 toolPermissions,JSON);[defaultFor] 的分级
 * 按工具副作用强度取默认,未知工具名(版本更迭)一律回退 [CONFIRM] —— 宁可多问不静默。
 * 单测见 ToolPermissionsTest。
 */
enum class ToolPermission {
    /** 免确认:只读、无副作用的工具。 */
    ALLOW,

    /** 每次执行前需用户确认。 */
    CONFIRM,

    /** 禁止执行:不执行、直接回传拒绝结果。 */
    DENY,
}

/**
 * 工具名 → 权限的映射与编解码。
 * - [defaultFor]:按副作用强度取默认分级(记忆写视为低风险写,记忆读/查询/计算免确认)
 * - [decode]/[encode]:settings 表 JSON 持久化;解码时丢弃未知分级与未知工具名
 * - [effective]:合并用户覆写与默认值,保证任何工具都有分级可查
 */
object ToolPermissions {

    /** settings 表存储键。 */
    const val SETTINGS_KEY = "toolPermissions"

    /** 未知工具的兜底分级:宁可多问一次,不静默执行。 */
    val FALLBACK: ToolPermission = ToolPermission.CONFIRM

    /** 各工具的默认分级(按副作用强度)。 */
    private val DEFAULTS: Map<String, ToolPermission> = mapOf(
        // 只读/无副作用:免确认
        "web_search" to ToolPermission.ALLOW,
        "web_fetch" to ToolPermission.ALLOW,
        "github_search_repositories" to ToolPermission.ALLOW,
        "github_get_file" to ToolPermission.ALLOW,
        "github_get_readme" to ToolPermission.ALLOW,
        "calculator" to ToolPermission.ALLOW,
        "current_time" to ToolPermission.ALLOW,
        "memory_recall" to ToolPermission.ALLOW,
        // 循环控制类:不是真实副作用,是循环的收工信号/子代理派发。
        // 早期漏登记的后果是每轮结束都弹「工具执行确认」,用户不点循环就卡住(真机实测)。
        AgentLoop.COMPLETION_TOOL to ToolPermission.ALLOW,
        Subagent.TOOL_NAME to ToolPermission.ALLOW,
        // 有写入或影响后续行为的:默认需确认
        "save_memory" to ToolPermission.CONFIRM,
    )

    /** 工具的生效权限:用户覆写优先,未覆写用默认,连工具名都未知时用 [FALLBACK]。 */
    fun effective(tool: String, overrides: Map<String, ToolPermission>): ToolPermission =
        overrides[tool] ?: DEFAULTS[tool] ?: FALLBACK

    /** 工具的默认分级(设置页展示「恢复默认」用)。 */
    fun defaultFor(tool: String): ToolPermission = DEFAULTS[tool] ?: FALLBACK

    /**
     * 从 JSON 解码(形如 {"web_search":"allow","save_memory":"confirm"})。
     * 未知分级值/未知工具名一律丢弃,保证版本更迭不产生脏状态。
     */
    fun decode(json: String): Map<String, ToolPermission> {
        if (json.isBlank()) return emptyMap()
        return try {
            val o = JSONObject(json)
            val out = mutableMapOf<String, ToolPermission>()
            for (key in o.keys()) {
                val tool = DefaultTools.ALL.firstOrNull { it.name == key } ?: continue
                val p = when (o.optString(key).lowercase()) {
                    "allow" -> ToolPermission.ALLOW
                    "confirm" -> ToolPermission.CONFIRM
                    "deny" -> ToolPermission.DENY
                    else -> continue
                }
                out[tool.name] = p
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** 编码为 JSON(settings 持久化格式);空映射写空对象。 */
    fun encode(map: Map<String, ToolPermission>): String =
        JSONObject().apply { map.forEach { (k, v) -> put(k, v.name.lowercase()) } }.toString()
}
