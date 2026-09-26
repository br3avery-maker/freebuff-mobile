package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具权限分级:默认值、编解码、合法化。 */
class ToolPermissionsTest {

    @Test
    fun `注册表能识别工具名,未知名字要挡住`() {
        // 实测场景:模型凭空造出 `browse_web` —— 它执行不了,不该弹权限确认
        assertTrue(DefaultTools.isKnown("calculator"))
        assertTrue(DefaultTools.isKnown("memory_recall"))
        assertFalse(DefaultTools.isKnown("browse_web"))
        assertFalse(DefaultTools.isKnown(""))
    }

    @Test
    fun `只读工具默认免确认 写入类默认需确认`() {
        assertEquals(ToolPermission.ALLOW, ToolPermissions.defaultFor("web_search"))
        assertEquals(ToolPermission.ALLOW, ToolPermissions.defaultFor("calculator"))
        assertEquals(ToolPermission.ALLOW, ToolPermissions.defaultFor("memory_recall"))
        assertEquals(ToolPermission.CONFIRM, ToolPermissions.defaultFor("save_memory"))
    }

    @Test
    fun `循环控制工具免确认`() {
        // task_completed / spawn_subagent 不是真实副作用:前者是循环收工信号,后者暂无本地执行。
        // 若回退到 CONFIRM,循环每收工一次就弹窗,用户不点就卡住(真机实测过的缺陷)。
        assertEquals(ToolPermission.ALLOW, ToolPermissions.effective(AgentLoop.COMPLETION_TOOL, emptyMap()))
        assertEquals(ToolPermission.ALLOW, ToolPermissions.effective(Subagent.TOOL_NAME, emptyMap()))
        assertTrue(AgentLoop.COMPLETION_TOOL in DefaultTools.ALL.map { it.name })
    }

    @Test
    fun `未知工具回退 CONFIRM`() {
        assertEquals(ToolPermission.CONFIRM, ToolPermissions.effective("mystery_tool", emptyMap()))
        // 未知工具 + 任意覆写仍是覆写值(用户显式设过就尊重)
        assertEquals(ToolPermission.DENY, ToolPermissions.effective("mystery_tool", mapOf("mystery_tool" to ToolPermission.DENY)))
    }

    @Test
    fun `覆写优先于默认`() {
        assertEquals(
            ToolPermission.DENY,
            ToolPermissions.effective("web_fetch", mapOf("web_fetch" to ToolPermission.DENY)),
        )
        assertEquals(
            ToolPermission.ALLOW,
            ToolPermissions.effective("save_memory", mapOf("save_memory" to ToolPermission.ALLOW)),
        )
    }

    @Test
    fun `编解码往返一致`() {
        val src = mapOf(
            "web_search" to ToolPermission.DENY,
            "save_memory" to ToolPermission.ALLOW,
            "memory_recall" to ToolPermission.CONFIRM,
        )
        val json = ToolPermissions.encode(src)
        assertTrue(json.contains("\"web_search\":\"deny\""))
        assertEquals(src, ToolPermissions.decode(json))
    }

    @Test
    fun `解码丢弃未知工具与未知分级`() {
        val decoded = ToolPermissions.decode("""{"web_search":"deny","hacker_tool":"allow","calculator":"banana"}""")
        assertEquals(mapOf("web_search" to ToolPermission.DENY), decoded)
    }

    @Test
    fun `解码容错空串与非法 JSON`() {
        assertTrue(ToolPermissions.decode("").isEmpty())
        assertTrue(ToolPermissions.decode("not json").isEmpty())
    }

    @Test
    fun `默认分级对全部注册工具都有定义`() {
        // 注册表里的每个工具都能查到分级(ALLOW/CONFIRM),不会有 null 路径漏到 FALLBACK 之外的意外
        DefaultTools.ALL.forEach { t ->
            val p = ToolPermissions.effective(t.name, emptyMap())
            assertTrue("工具 ${t.name} 应有分级", p == ToolPermission.ALLOW || p == ToolPermission.CONFIRM)
        }
    }
}
