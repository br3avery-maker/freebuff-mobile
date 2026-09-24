package com.freebuff.core.model

/**
 * 会话级工具权限记忆(「本次会话记住选择」):
 * - 用户在确认弹窗勾选后,同一工具在同一会话内的后续调用按记住的决定执行,不再弹窗
 * - 纯内存、不落库:会话关闭即失效,下次会话回到设置页配置的分级 —— 避免临时放宽被永久固化
 * - 只记 [ToolPermission.ALLOW] / [ToolPermission.DENY](CONFIRM 本身就是「每次都问」,无需记)
 * - 决策顺序:会话记忆 > 设置覆写/默认分级;记 DENY 时模型收到与单次拒绝相同的回传文案
 *
 * 纯类无协程依赖,单测见 SessionPermissionMemoryTest。
 */
class SessionPermissionMemory {

    private val remembered = mutableMapOf<String, ToolPermission>()

    /** 是否已记住该工具的决定。 */
    fun has(tool: String): Boolean = remembered.containsKey(tool)

    /** 查询记住的决定;未记住返回 null(调用方应继续走弹窗/分级流程)。 */
    fun get(tool: String): ToolPermission? = remembered[tool]

    /** 记住一次决定(允许/禁止);CONFIRM 不可记(语义即「每次都问」)。 */
    fun remember(tool: String, permission: ToolPermission) {
        if (permission == ToolPermission.CONFIRM) return
        remembered[tool] = permission
    }

    /** 清空全部记忆(会话切换/流结束时调用)。 */
    fun clear() = remembered.clear()
}
