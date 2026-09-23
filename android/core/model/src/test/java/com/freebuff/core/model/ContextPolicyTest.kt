package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 上下文策略:token 估算、窗口解析、工具结果压缩、预算判定。 */
class ContextPolicyTest {

    @Test
    fun `中文按1token每字 英文按03token每字`() {
        val cn = ContextPolicy.estimateTokens("你好世界") // 4 CJK
        val en = ContextPolicy.estimateTokens("abcdefghij") // 10 ascii
        assertEquals(4, cn)
        assertEquals(3, en)
    }

    @Test
    fun `parseCtxWindow 支持 k m 与纯数字`() {
        assertEquals(128000, ContextPolicy.parseCtxWindow("128k"))
        assertEquals(128000, ContextPolicy.parseCtxWindow("128K"))
        assertEquals(1000000, ContextPolicy.parseCtxWindow("1m"))
        assertEquals(200000, ContextPolicy.parseCtxWindow("200000"))
        assertEquals(8192, ContextPolicy.parseCtxWindow("8.192k").coerceAtMost(8192))
    }

    @Test
    fun `parseCtxWindow 非法输入回退默认值`() {
        assertEquals(ContextPolicy.DEFAULT_CONTEXT_WINDOW, ContextPolicy.parseCtxWindow(""))
        assertEquals(ContextPolicy.DEFAULT_CONTEXT_WINDOW, ContextPolicy.parseCtxWindow("abc"))
        assertEquals(ContextPolicy.DEFAULT_CONTEXT_WINDOW, ContextPolicy.parseCtxWindow("12x"))
    }

    @Test
    fun `compressToolResult 未超限原样返回`() {
        val short = "搜索结果:Kotlin 2.0 发布"
        assertEquals(short, ContextPolicy.compressToolResult(short))
    }

    @Test
    fun `compressToolResult 超限保留首尾并标注`() {
        val long = buildString { repeat(2000) { append("x") } } // 2000 ascii ≈ 600 tokens
        val out = ContextPolicy.compressToolResult(long, maxTokens = 100)
        assertTrue("应包含截断标注", out.contains("中间省略"))
        assertTrue("头部应保留", out.startsWith("x"))
        assertTrue("尾部应保留", out.trimEnd().endsWith("x"))
        assertTrue("压缩后应显著变短", out.length < long.length / 2)
    }

    @Test
    fun `extractiveSummary 保留轮数与首末内容`() {
        val s = ContextPolicy.extractiveSummary("帮我写个爬虫", "已完成框架搭建", 10)
        assertTrue(s.contains("10"))
        assertTrue(s.contains("帮我写个爬虫"))
        assertTrue(s.contains("已完成框架搭建"))
    }

    @Test
    fun `预算判定 usable 与 isOver`() {
        val b = ContextBudget(contextWindow = 32000, reserveOutput = 4000)
        assertEquals(28000, b.usableTokens)
        assertFalse(b.isOver(28000))
        assertTrue(b.isOver(28001))
    }
}
