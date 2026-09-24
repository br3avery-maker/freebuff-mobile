package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话级权限记忆:记住/查询/清空与 CONFIRM 不可记语义。 */
class SessionPermissionMemoryTest {

    @Test
    fun `记住 ALLOW 与 DENY 后可查询`() {
        val m = SessionPermissionMemory()
        m.remember("web_search", ToolPermission.ALLOW)
        m.remember("run_terminal_command", ToolPermission.DENY)
        assertEquals(ToolPermission.ALLOW, m.get("web_search"))
        assertEquals(ToolPermission.DENY, m.get("run_terminal_command"))
        assertTrue(m.has("web_search"))
    }

    @Test
    fun `未记住返回 null`() {
        val m = SessionPermissionMemory()
        assertNull(m.get("web_search"))
        assertFalse(m.has("web_search"))
    }

    @Test
    fun `CONFIRM 不可记住`() {
        val m = SessionPermissionMemory()
        m.remember("web_search", ToolPermission.CONFIRM)
        assertNull(m.get("web_search"))
        assertFalse(m.has("web_search"))
    }

    @Test
    fun `clear 清空全部记忆`() {
        val m = SessionPermissionMemory()
        m.remember("web_search", ToolPermission.ALLOW)
        m.clear()
        assertNull(m.get("web_search"))
    }
}
