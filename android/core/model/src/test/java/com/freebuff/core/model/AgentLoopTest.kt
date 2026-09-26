package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** agent 循环延续策略:早停(几句话就停)的回归守卫。 */
class AgentLoopTest {

    @Test
    fun `还在调工具就继续`() {
        assertEquals(
            AgentLoop.Decision.Continue,
            AgentLoop.decide(toolRounds = 3, toolCalls = 2, toolsEnabled = true, nudgesUsed = 0),
        )
    }

    @Test
    fun `开启工具但一次没用就答完,正常收工`() {
        // 普通聊天:模型没动过工具、直接回答 —— 不能变成「必须调工具」而反复追问
        assertEquals(
            AgentLoop.Decision.Stop,
            AgentLoop.decide(toolRounds = 0, toolCalls = 0, toolsEnabled = true, nudgesUsed = 0),
        )
    }

    @Test
    fun `动过工具后只回文本,提醒继续而不是收工`() {
        val d = AgentLoop.decide(toolRounds = 2, toolCalls = 0, toolsEnabled = true, nudgesUsed = 0)
        assertTrue(d is AgentLoop.Decision.Nudge)
        assertTrue((d as AgentLoop.Decision.Nudge).message.contains(AgentLoop.COMPLETION_TOOL))
    }

    @Test
    fun `提醒有上限,用完交给用户`() {
        assertEquals(
            AgentLoop.Decision.Stop,
            AgentLoop.decide(
                toolRounds = 2, toolCalls = 0, toolsEnabled = true,
                nudgesUsed = AgentLoop.MAX_CONTINUE_NUDGES,
            ),
        )
    }

    @Test
    fun `声明完成就直接收工`() {
        assertEquals(
            AgentLoop.Decision.Stop,
            AgentLoop.decide(toolRounds = 5, toolCalls = 1, toolsEnabled = true, nudgesUsed = 1, completed = true),
        )
        // 完成工具与别的工具同时返回时也以完成为准
        assertTrue("task_completed" in AgentLoop.END_TOOLS)
    }

    @Test
    fun `未开启工具时不循环`() {
        assertEquals(
            AgentLoop.Decision.Stop,
            AgentLoop.decide(toolRounds = 0, toolCalls = 0, toolsEnabled = false, nudgesUsed = 0),
        )
    }

    // ---------- 重复调用(原地打转) ----------
    @Test
    fun `整轮重复调用先提醒一次`() {
        val d = AgentLoop.decide(
            toolRounds = 3, toolCalls = 3, toolsEnabled = true, nudgesUsed = 0, repeatRounds = 1,
        )
        assertTrue("首轮重复应先提醒,实际 $d", d is AgentLoop.Decision.Nudge)
        assertTrue((d as AgentLoop.Decision.Nudge).message.contains(AgentLoop.COMPLETION_TOOL))
    }

    @Test
    fun `继续重复就直接停并给出可见提示`() {
        val d = AgentLoop.decide(
            toolRounds = 4, toolCalls = 3, toolsEnabled = true, nudgesUsed = 1,
            repeatRounds = AgentLoop.MAX_REPEAT_ROUNDS,
        )
        assertTrue("再重复应停下,实际 $d", d is AgentLoop.Decision.StopWithNote)
        val note = (d as AgentLoop.Decision.StopWithNote).note
        assertTrue(note.contains("重复调用"))
        assertTrue(note.contains("继续"))
    }

    @Test
    fun `声明完成优先于重复检测`() {
        assertEquals(
            AgentLoop.Decision.Stop,
            AgentLoop.decide(
                toolRounds = 4, toolCalls = 3, toolsEnabled = true, nudgesUsed = 0,
                completed = true, repeatRounds = 3,
            ),
        )
    }

    @Test
    fun `换参数或换工具后重复计数归零(由调用方重置)`() {
        // repeatRounds = 0 表示本轮有新进展:按正常工具轮继续
        assertEquals(
            AgentLoop.Decision.Continue,
            AgentLoop.decide(
                toolRounds = 4, toolCalls = 2, toolsEnabled = true, nudgesUsed = 2, repeatRounds = 0,
            ),
        )
    }

    @Test
    fun `轮次上限兜底并给出可见提示`() {
        val d = AgentLoop.decide(
            toolRounds = AgentLoop.MAX_TOOL_ROUNDS, toolCalls = 3, toolsEnabled = true, nudgesUsed = 0,
        )
        assertTrue(d is AgentLoop.Decision.StopWithNote)
        val note = (d as AgentLoop.Decision.StopWithNote).note
        assertTrue(note.contains(AgentLoop.MAX_TOOL_ROUNDS.toString()))
        assertTrue(note.contains("继续"))
        // 上限要远大于早期实现的 6 轮(否则长任务依旧「几句话就停」)
        assertTrue(AgentLoop.MAX_TOOL_ROUNDS >= 20)
    }
}
