package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具重复调用追踪:签名归一化、结果复用与计数。 */
class ToolRepeatTrackerTest {

    private fun call(name: String, args: String) = ToolCallReq("c-" + name + args, name, args)

    @Test
    fun `同一工具同一参数算同一次调用`() {
        val t = ToolRepeatTracker()
        val a = t.signature(call("web_fetch", "{\"url\":\"https://example.com\"}"))
        val b = t.signature(call("web_fetch", "{\"url\":\"https://example.com\"}"))
        assertEquals(a, b)
        assertFalse(t.isRepeat(a))
        t.remember(a, "结果")
        assertTrue(t.isRepeat(b))
        assertEquals("结果", t.cachedOutput(b))
    }

    @Test
    fun `参数里的空白差异不算不同调用`() {
        val t = ToolRepeatTracker()
        val pretty = t.signature(call("calculator", "{\n  \"expression\" : \"1+1\"\n}"))
        val compact = t.signature(call("calculator", "{\"expression\":\"1+1\"}"))
        assertEquals(pretty, compact)
    }

    @Test
    fun `字符串内部的空白必须保留(空格可能是有意义的)`() {
        val t = ToolRepeatTracker()
        val a = t.signature(call("calculator", "{\"expression\":\"1 + 1\"}"))
        val b = t.signature(call("calculator", "{\"expression\":\"1+1\"}"))
        assertTrue("字符串里的空格不同就不能当作同一次调用", a != b)
    }

    @Test
    fun `换工具或换参数都算新调用`() {
        val t = ToolRepeatTracker()
        val a = t.signature(call("calculator", "{\"expression\":\"1+1\"}"))
        assertFalse(t.isRepeat(t.signature(call("calculator", "{\"expression\":\"2+2\"}"))))
        assertFalse(t.isRepeat(t.signature(call("current_time", "{}"))))
        assertEquals(0, t.distinctCount())
        t.remember(a, "2")
        assertEquals(1, t.distinctCount())
        assertNull(t.cachedOutput(t.signature(call("calculator", "{\"expression\":\"2+2\"}"))))
    }
}

/** 状态牌步骤:过程性步骤收尾要清掉,历史性步骤保留。 */
class MsgStepsTest {

    @Test
    fun `连接与轮次是过程性的`() {
        assertTrue(MsgSteps.isTransient(MsgSteps.CONNECT))
        assertTrue(MsgSteps.isTransient(MsgSteps.round(1)))
        assertTrue(MsgSteps.isTransient(MsgSteps.round(12)))
    }

    @Test
    fun `重试记录是历史性的要保留`() {
        assertFalse(MsgSteps.isTransient(MsgSteps.retry(1)))
        assertFalse(MsgSteps.isTransient("其他步骤"))
    }

    @Test
    fun `收尾只清过程性步骤`() {
        val steps = listOf(
            MsgStep(MsgSteps.CONNECT, "Connecting to the model…"),
            MsgStep(MsgSteps.retry(1), "上次失败:…"),
            MsgStep(MsgSteps.round(2), "调用 calculator"),
        )
        assertEquals(listOf(MsgStep(MsgSteps.retry(1), "上次失败:…")), MsgSteps.withoutTransient(steps))
    }

    @Test
    fun `轮次文案含轮数、调用文案列出工具`() {
        assertEquals("Round 3", MsgSteps.round(3))
        assertEquals("Generating reply…", MsgSteps.calling(emptyList()))
        assertEquals("Calling current_time, calculator", MsgSteps.calling(listOf("current_time", "calculator")))
    }

    @Test
    fun `结果摘要取每条的首个非空行`() {
        val d = MsgSteps.digest(
            listOf(
                "Calculator" to "\n\n25",
                "Current time" to "2026-09-26 05:24:50\n其他行",
            ),
        )
        assertTrue(d, d.contains("· Calculator:25"))
        assertTrue(d, d.contains("· Current time:2026-09-26 05:24:50"))
        assertFalse(d, d.contains("其他行"))
    }

    @Test
    fun `结果摘要超长截断、空列表返回空串`() {
        assertEquals("", MsgSteps.digest(emptyList()))
        val long = MsgSteps.digest(listOf("Fetch webpage" to "x".repeat(500)))
        assertTrue(long, long.contains("x".repeat(160)))
        assertFalse(long, long.contains("x".repeat(161)))
    }

    @Test
    fun `停下来时的提示不带前导空行(分隔由调用方加)`() {
        assertFalse(AgentLoop.repeatStopNote(2).startsWith("\n"))
        assertFalse(AgentLoop.ROUND_LIMIT_NOTE.startsWith("\n"))
    }

    @Test
    fun `停止且无内容时给提示,已有内容时只加尾标注`() {
        assertEquals(MsgSteps.STOPPED_NOTE, MsgSteps.stoppedText(""))
        assertEquals(MsgSteps.STOPPED_NOTE, MsgSteps.stoppedText("   \n"))
        val partial = MsgSteps.stoppedText("已经写了半句")
        assertTrue(partial, partial.startsWith("已经写了半句"))
        assertTrue(partial, partial.endsWith("The partial reply is shown above.)"))
        // 幂等:两条收尾路径顺序不定,重复标注会让界面出现两遍
        assertEquals(MsgSteps.STOPPED_NOTE, MsgSteps.stoppedText(MsgSteps.STOPPED_NOTE))
        assertEquals(partial, MsgSteps.stoppedText(partial))
    }

    @Test
    fun `提醒消息走 user 角色并带系统前缀`() {
        assertEquals("user", AgentLoop.REMINDER_ROLE)
        assertTrue(AgentLoop.NUDGE_TEXT.startsWith(AgentLoop.REMINDER_PREFIX))
        assertTrue(AgentLoop.REPEAT_NUDGE_TEXT.startsWith(AgentLoop.REMINDER_PREFIX))
    }
}
