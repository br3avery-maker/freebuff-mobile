package com.freebuff.core.data.tools

import com.freebuff.core.model.ToolCallReq
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 端侧工具执行器:本地工具(计算器/时间)真实执行,未知工具与坏参数走错误路径。 */
class ToolExecutorsTest {
    private val ex = ToolExecutors(OkHttpClient())

    @Test
    fun `计算器 四则运算与括号`() = runBlocking {
        val r = ex.execute(ToolCallReq("c1", "calculator", """{"expression":"(12+8)*3.5"}"""))
        assertFalse(r.isError)
        assertEquals("(12+8)*3.5 = 70", r.content)
    }

    @Test
    fun `计算器 非法表达式返回错误结果`() = runBlocking {
        val r = ex.execute(ToolCallReq("c2", "calculator", """{"expression":"12++"}"""))
        assertTrue(r.isError)
        assertTrue(r.content.contains("计算失败"))
    }

    @Test
    fun `缺少参数返回错误结果`() = runBlocking {
        val r = ex.execute(ToolCallReq("c3", "calculator", "{}"))
        assertTrue(r.isError)
        val r2 = ex.execute(ToolCallReq("c4", "web_fetch", "{}"))
        assertTrue(r2.isError)
    }

    @Test
    fun `未知工具返回错误结果而非抛异常`() = runBlocking {
        val r = ex.execute(ToolCallReq("c5", "no_such_tool", "{}"))
        assertTrue(r.isError)
        assertTrue(r.content.contains("未知工具"))
    }

    @Test
    fun `current_time 返回可读时间`() = runBlocking {
        val r = ex.execute(ToolCallReq("c6", "current_time", "{}"))
        assertFalse(r.isError)
        assertTrue(r.content.contains(Regex("""\d{4}-\d{2}-\d{2}""")))
    }
}
