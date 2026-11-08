package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具错误信封:形状稳定、带处方(类型 / 问题 / 怎么改 / 示例)、连续失败升级为「换路」。
 *
 * 这些断言就是「模型能不能学会」的底线 —— 信封一旦失去固定形状或没了处方,
 * 弱模型就会把报错当答案,或者拿着同一个错参数反复撞。
 */
class ToolErrorsTest {

    @Test
    fun `信封是固定形状 带类型标签与处方`() {
        val env = ToolErrors.report(
            ToolErrors.Kind.MISSING_PARAM, "github_get_file",
            "Required argument path is missing", "Add path and retry once.",
            example = "github_get_file({\"owner\":\"a\",\"repo\":\"b\",\"path\":\"README.md\"})",
        )

        assertTrue("必须能被识别为错误结果", env.startsWith(ToolErrors.MARK))
        assertTrue("中文名 + 稳定英文标签", env.contains("Type=Missing required argument/MISSING_PARAM"))
        assertTrue(env.contains("Tool=github_get_file"))
        assertTrue(env.contains("\nProblem: Required argument path is missing"))
        assertTrue(env.contains("\nHow to fix: Add path and retry once."))
        assertTrue("要给出可照抄的调用示例", env.contains("Correct call example: github_get_file("))
        assertTrue("第一次失败不该劝退", !env.contains(ToolErrors.ESCALATE))
    }

    @Test
    fun `同一工具连续失败第二次起要求换路`() {
        val env = ToolErrors.report(
            ToolErrors.Kind.BAD_VALUE, "calculator",
            "表达式「1+」无法解析", "换一个合法算式重试", attempt = 2,
        )
        assertTrue(env.contains("Attempt 2 failed"))
        assertTrue(env.contains(ToolErrors.ESCALATE))
        assertTrue("要给出路而不是让它无限重试", env.contains("Change arguments, choose another tool"))
    }

    @Test
    fun `未知工具给近邻建议与完整清单`() {
        val env = ToolErrors.unknownTool("get_files", listOf("web_search", "github_get_file", "calculator"))
        assertTrue("拼写相近的名字要指出来", env.contains("github_get_file"))
        assertTrue("清单要完整", env.contains("calculator"))
        assertTrue(env.contains("UNKNOWN_TOOL"))
    }

    @Test
    fun `幻觉出来的工具名也能给建议`() {
        // 实测:模型会凭空调 browse_web —— 词面不相似,但共享 web 这个词根
        val env = ToolErrors.unknownTool("browse_web", DefaultTools.names())
        assertTrue("应建议 web_* 工具", env.contains("web_search") || env.contains("web_fetch"))
        assertTrue("至少要有完整可用清单", env.contains("calculator"))
    }

    @Test
    fun `HTTP 状态码分别给出下一步`() {
        assertTrue(ToolErrors.http("web_fetch", 401, "").contains("AUTH"))
        assertTrue(ToolErrors.http("web_fetch", 403, "").contains("Authentication or permission required"))

        val notFound = ToolErrors.http("github_get_file", 404, "")
        assertTrue(notFound.contains("NOT_FOUND"))
        assertTrue("404 要提示核对拼写", notFound.contains("spelling"))

        val limited = ToolErrors.http("web_search", 429, "")
        assertTrue(limited.contains("RATE_LIMIT"))
        assertTrue("限流要说明别连续重试", limited.contains("do not retry repeatedly"))

        assertTrue(ToolErrors.http("web_search", 503, "").contains("NETWORK"))

        val hinted = ToolErrors.http("github_get_file", 404, "", hint = "先用 github_search_repositories 搜仓库")
        assertTrue("工具特有的下一步要带上", hinted.contains("github_search_repositories"))
    }

    @Test
    fun `单条参数问题保持固定形状 但给全参数清单与示例`() {
        val tool = DefaultTools.find("github_get_file")!!
        val env = ToolErrors.forProblems(
            tool,
            listOf(
                ToolErrors.ArgProblem.Missing("path"),
                ToolErrors.ArgProblem.Range("limit", "99", 1, 10),
            ).take(1),
        )
        assertTrue(env.contains("Missing required argument"))
        assertTrue("一次只讲一件事", !env.contains("outside the allowed range"))
        assertTrue("要给参数名 + 类型清单", env.contains("owner:string"))
        assertTrue("要给可照抄的示例", env.contains(tool.example))
    }

    @Test
    fun `多个参数问题合并进一张信封 逐条编号给处方`() {
        val tool = DefaultTools.find("github_get_file")!!
        val env = ToolErrors.forProblems(
            tool,
            listOf(
                ToolErrors.ArgProblem.Missing("path"),
                ToolErrors.ArgProblem.Unknown("mode"),
                ToolErrors.ArgProblem.Range("limit", "99", null, 20),
            ),
        )
        assertTrue("仍然是一条错误结果", env.startsWith(ToolErrors.MARK))
        assertTrue("问题要逐条编号", env.contains("① Required argument path"))
        assertTrue(env.contains("② Argument name mode is unknown"))
        assertTrue(env.contains("③ Argument limit has value"))
        assertTrue("每条问题都要有对应的改法", env.contains("① Add path"))
        assertTrue(env.contains("② Remove the undeclared argument “mode”"))
        assertTrue(env.contains("③ limit must be within"))
        assertTrue("末尾仍要附参数清单", env.contains("owner:string"))
        assertTrue("示例仍要给", env.contains(tool.example))
        assertTrue("类型取最严重的一类", env.contains("MISSING_PARAM"))
        assertTrue("合并也不破坏固定行形状", env.lines().let { l -> l.size <= 4 && l[0].startsWith(ToolErrors.MARK) })
    }

    @Test
    fun `长文案单行化并截断 不把堆栈丢给模型`() {
        val env = ToolErrors.internalError(
            "web_fetch",
            "java.lang.IllegalStateException: boom\n\tat Foo.bar(Foo.kt:1)\n" + "x".repeat(400),
        )
        assertTrue("换行要压成单行", !env.contains("\n\t"))
        assertTrue(
            "不能出现堆栈行(以 at 开头的行)",
            env.lines().none { it.trimStart().startsWith("at ") },
        )
        assertEquals("信封只有固定的几行", 3, env.lines().size)
        val problemLine = env.substringAfter("Problem: ").substringBefore('\n')
        assertTrue("问题行要截断: ${problemLine.length}", problemLine.length <= 220)
    }

    @Test
    fun `熔断信封直接拒绝并列出可用的替代工具`() {
        val env = ToolErrors.breakerOpen(
            "web_fetch",
            listOf("web_search", "web_fetch", "calculator", "current_time", "task_completed"),
            attempt = 3,
        )
        assertTrue(env.startsWith(ToolErrors.MARK))
        assertTrue(env.contains("CIRCUIT_OPEN"))
        assertTrue("要说清这次没执行", env.contains("this call did not run"))
        assertTrue("要说清怎么恢复", env.contains("resets on the next user message"))
        assertTrue("要列出替代工具且不含被熔断的它", env.contains("web_search") && !env.contains("available: web_search, web_fetch"))
    }

    @Test
    fun `重复调用提示复用并劝停重发`() {
        val note = ToolErrors.duplicateNote("calculator", "9*9 = 81")
        assertTrue(note.contains("cached result was reused"))
        assertTrue(note.contains("instead of repeating the call"))
        assertTrue("原结果要在", note.contains("9*9 = 81"))
    }
}
