package com.freebuff.core.data.tools

import com.freebuff.core.model.ToolCallReq
import com.freebuff.core.model.ToolErrors
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
    fun `计算器 非法表达式回错误信封并给处方`() = runBlocking {
        val r = ex.execute(ToolCallReq("c2", "calculator", """{"expression":"12++"}"""))
        assertTrue(r.isError)
        assertTrue("要是固定形状的错误信封", r.content.startsWith(ToolErrors.MARK))
        assertTrue(r.content.contains("BAD_VALUE"))
        assertTrue("要说清怎么改", r.content.contains("怎么改: "))
        assertTrue("要给可照抄的示例", r.content.contains("calculator({"))
    }

    @Test
    fun `缺少参数回错误信封并列出参数清单`() = runBlocking {
        val r = ex.execute(ToolCallReq("c3", "calculator", "{}"))
        assertTrue(r.isError)
        assertTrue(r.content.startsWith(ToolErrors.MARK))
        assertTrue(r.content.contains("MISSING_PARAM"))
        assertTrue("要列出参数名 + 类型", r.content.contains("expression:string"))

        val r2 = ex.execute(ToolCallReq("c4", "web_fetch", "{}"))
        assertTrue(r2.isError)
        assertTrue(r2.content.contains("url:string"))
    }

    @Test
    fun `同一次调用的多个参数问题合并进一张信封`() = runBlocking {
        // 一次带三个错:自造 mode + 缺 query + limit 越界 —— 一张信封逐条编号全讲完,
        // 弱模型不用再「改一个错、重发、再撞下一个」地耗轮次
        val r = ex.execute(
            ToolCallReq(
                "c11", "github_search_repositories",
                """{"mode":"fast","limit":99}""",
            ),
        )
        assertTrue(r.isError)
        assertTrue("实际信封: ${r.content}", r.content.startsWith(ToolErrors.MARK))
        assertTrue("实际信封: ${r.content}", env_count(r.content) >= 3)
        assertTrue("实际信封: ${r.content}", r.content.contains("① 参数名 mode 不存在"))
        assertTrue("实际信封: ${r.content}", r.content.contains("② 必填参数 query"))
        assertTrue("实际信封: ${r.content}", r.content.contains("③ 参数 limit 的取值"))
        assertTrue("每条都要有改法: ${r.content}", r.content.contains("③ limit 取值需在 1~10"))
    }

    /** 数信封里的编号条数(①②③…)。 */
    private fun env_count(s: String): Int = listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨")
        .count { s.contains(it) }

    @Test
    fun `参数名写错能靠别名救回 自造参数会被挡下`() = runBlocking {
        // 弱模型常见错法:把 path 写成 file —— 别名归一后照常执行
        val ok = ex.execute(
            ToolCallReq("c7", "calculator", """{"expr":"6*7"}"""),
        )
        assertFalse("别名 expr 应被接受", ok.isError)
        assertTrue(ok.content.contains("6*7 = 42"))

        // 真正的自造参数:回信封并告诉它合法参数名
        val bad = ex.execute(ToolCallReq("c8", "calculator", """{"expression":"1+1","mode":"fast"}"""))
        assertTrue(bad.isError)
        assertTrue(bad.content.contains("UNKNOWN_PARAM"))
        assertTrue(bad.content.contains("mode"))
    }

    @Test
    fun `未知工具回带建议的错误信封而非抛异常`() = runBlocking {
        val r = ex.execute(ToolCallReq("c5", "no_such_tool", "{}"))
        assertTrue(r.isError)
        assertTrue(r.content.startsWith(ToolErrors.MARK))
        assertTrue(r.content.contains("UNKNOWN_TOOL"))
        assertTrue("要列出可用工具", r.content.contains("web_search"))
    }

    @Test
    fun `连续失败第二次会劝换路`() = runBlocking {
        val first = ex.execute(ToolCallReq("c9", "calculator", """{"expression":"1+"}"""), attempt = 1)
        assertTrue(!first.content.contains(ToolErrors.ESCALATE))
        val second = ex.execute(ToolCallReq("c10", "calculator", """{"expression":"1+"}"""), attempt = 2)
        assertTrue("第 2 次失败要升级为换路", second.content.contains(ToolErrors.ESCALATE))
    }

    @Test
    fun `current_time 返回可读时间`() = runBlocking {
        val r = ex.execute(ToolCallReq("c6", "current_time", "{}"))
        assertFalse(r.isError)
        assertTrue(r.content.contains(Regex("""\d{4}-\d{2}-\d{2}""")))
    }
}
