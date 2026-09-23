package com.freebuff.core.data.context

import com.freebuff.core.model.ChatMsg
import com.freebuff.core.model.ContextBudget
import com.freebuff.core.model.ContextPolicy
import com.freebuff.core.model.MemoryBlock
import com.freebuff.core.model.ToolCard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 上下文构建器:记忆注入、工具结果压缩、预算裁剪 + 摘要、统计。 */
class ContextBuilderTest {

    private fun msg(role: String, text: String, tools: List<ToolCard> = emptyList()) =
        ChatMsg(id = role + "-" + Math.random(), role = role, text = text, tools = tools)

    @Test
    fun `短会话不压缩 记忆块进入 system`() = runTest {
        val built = ContextBuilder().build(
            memoryBlocks = listOf(MemoryBlock("user", "偏好中文")),
            session = listOf(msg("user", "你好"), msg("agent", "你好!")),
            budget = ContextBudget(contextWindow = 32000),
        )
        assertEquals(3, built.messages.size) // system + 2 条
        assertTrue(built.messages[0].content.contains("<memory_block name=\"user\">"))
        assertTrue(built.messages[0].content.contains("偏好中文"))
        assertEquals("user", built.messages[1].role)
        assertEquals("assistant", built.messages[2].role)
        assertTrue(built.stats.totalTokens > 0)
        assertTrue(!built.stats.didCompact)
    }

    @Test
    fun `超长工具结果被机械压缩`() = runTest {
        val longOutput = "y".repeat(8000) // ≈2400 tokens,超过单条 1200 上限
        val built = ContextBuilder().build(
            memoryBlocks = emptyList(),
            session = listOf(
                msg("agent", "我查一下", tools = listOf(ToolCard(callId = "c1", tool = "web_search", input = "q", output = longOutput, state = "done"))),
            ),
            budget = ContextBudget(contextWindow = 32000),
        )
        val agentMsg = built.messages.last()
        assertTrue("工具结果应被压缩", agentMsg.content.contains("中间省略"))
        assertTrue(agentMsg.content.length < longOutput.length)
        assertTrue(built.stats.truncatedToolResults >= 1)
    }

    @Test
    fun `超预算时旧消息被裁剪为摘要`() = runTest {
        // 预算 2000 token,塞 40 条长消息 → 必然裁剪
        val session = (1..40).map { i -> msg(if (i % 2 == 1) "user" else "agent", "消息 $i " + "内容".repeat(60)) }
        val built = ContextBuilder().build(
            memoryBlocks = emptyList(),
            session = session,
            budget = ContextBudget(contextWindow = 4000, reserveOutput = 2000),
        )
        assertTrue("应发生压缩 stats=" + built.stats, built.stats.didCompact)
        assertTrue(
            "应保留摘要 stats=" + built.stats + " parts=" + built.messages.map { it.role + ":" + it.content.take(30) },
            built.messages.any { it.content.startsWith(ContextBuilder.SUMMARY_MARK) },
        )
        assertTrue("近端消息应保留", built.messages.count { it.role == "user" || it.role == "assistant" } >= 4)
        // 总量应在预算内
        assertTrue(
            "总量 ${built.stats.totalTokens} 应在 ${built.stats.usableOf()} 内",
            built.stats.totalTokens <= built.stats.usableOf() + 200,
        )
    }

    @Test
    fun `摘要执行器失败时回退提取式`() = runTest {
        val session = (1..40).map { i -> msg(if (i % 2 == 1) "user" else "agent", "第 $i 轮 " + "数据".repeat(80)) }
        val built = ContextBuilder().build(
            memoryBlocks = emptyList(),
            session = session,
            budget = ContextBudget(contextWindow = 4000, reserveOutput = 2000),
            summarize = { throw RuntimeException("llm down") },
        )
        assertTrue(built.stats.didCompact)
        val summary = built.messages.first { it.content.startsWith(ContextBuilder.SUMMARY_MARK) }
        assertTrue("回退摘要应含早期请求", summary.content.contains("第 1 轮"))
    }

    @Test
    fun `LLM 摘要成功时使用模型输出`() = runTest {
        val session = (1..40).map { i -> msg(if (i % 2 == 1) "user" else "agent", "第 $i 轮 " + "数据".repeat(80)) }
        val built = ContextBuilder().build(
            memoryBlocks = emptyList(),
            session = session,
            budget = ContextBudget(contextWindow = 4000, reserveOutput = 2000),
            summarize = { "LLM 生成的摘要" },
        )
        val summary = built.messages.first { it.content.startsWith(ContextBuilder.SUMMARY_MARK) }
        assertTrue(summary.content.contains("LLM 生成的摘要"))
    }

    @Test
    fun `空会话仅返回 system`() = runTest {
        val built = ContextBuilder().build(
            memoryBlocks = emptyList(),
            session = emptyList(),
            budget = ContextBudget(contextWindow = 32000),
        )
        assertEquals(1, built.messages.size)
        assertEquals("system", built.messages[0].role)
        assertTrue(built.messages[0].content.contains("Freebuff 助手"))
    }

    private fun com.freebuff.core.model.ContextStats.usableOf(): Int =
        4000 - 2000 // 与测试用 budget 对齐(usableTokens 语义在 ContextBudget 内)
}
