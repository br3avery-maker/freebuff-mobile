package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 思维链参数族识别与请求参数生成。 */
class ReasoningTest {

    @Test
    fun `OpenAI 推理模型族识别为 EFFORT 并抬高补全预算`() {
        listOf("o1", "o3-mini", "o4-mini", "gpt-5", "gpt-5.1", "gpt-5-codex", "gpt-oss-120b").forEach { id ->
            assertEquals("flavor=$id", ReasoningFlavor.EFFORT, Reasoning.flavorOf(id))
        }
        val plan = Reasoning.plan(Reasoning.MODE_AUTO, "gpt-5-codex")!!
        assertEquals(ReasoningFlavor.EFFORT, plan.flavor)
        // 思考 token 与正文共享补全预算:不抬高会被「想完就没额度」截断
        assertEquals(Reasoning.MAX_COMPLETION_TOKENS, plan.maxCompletionTokens)
    }

    @Test
    fun `各家思考开关字段按族归属`() {
        assertEquals(ReasoningFlavor.THINKING_BUDGET, Reasoning.flavorOf("claude-sonnet-4.5"))
        assertEquals(ReasoningFlavor.THINKING_BUDGET, Reasoning.flavorOf("glm-4.6"))
        assertEquals(ReasoningFlavor.ENABLE_THINKING, Reasoning.flavorOf("qwen3-235b-a22b"))
        assertEquals(ReasoningFlavor.DEFAULT_ON, Reasoning.flavorOf("deepseek-reasoner"))
        assertEquals(ReasoningFlavor.DEFAULT_ON, Reasoning.flavorOf("QwQ-32B"))
        assertEquals(ReasoningFlavor.DEFAULT_ON, Reasoning.flavorOf("kimi-k2-thinking"))
    }

    @Test
    fun `不认识的模型不送任何参数`() {
        listOf("gpt-4o", "deepseek-chat", "glm-4-flash", "qwen2.5-72b", "llama-3.1-70b", "").forEach { id ->
            assertEquals("flavor=$id", ReasoningFlavor.UNKNOWN, Reasoning.flavorOf(id))
            assertNull("plan=$id", Reasoning.plan(Reasoning.MODE_AUTO, id))
        }
    }

    @Test
    fun `关闭时不送参数,默认模型也一并关掉`() {
        assertNull(Reasoning.plan(Reasoning.MODE_OFF, "deepseek-reasoner"))
        assertNull(Reasoning.plan(Reasoning.MODE_OFF, "gpt-5"))
    }

    @Test
    fun `强制开启时用最常见的开关试未识别模型`() {
        val plan = Reasoning.plan(Reasoning.MODE_ON, "my-private-reasoning-model")!!
        assertEquals(ReasoningFlavor.ENABLE_THINKING, plan.flavor)
        assertEquals(0, plan.maxCompletionTokens)
        // 识别得出来时仍按该族的字段走
        assertEquals(ReasoningFlavor.THINKING_BUDGET, Reasoning.plan(Reasoning.MODE_ON, "glm-4.6")!!.flavor)
    }

    @Test
    fun `三态取值与设置项一致`() {
        assertEquals(setOf("off", "auto", "on"), setOf(Reasoning.MODE_OFF, Reasoning.MODE_AUTO, Reasoning.MODE_ON))
        assertTrue(Reasoning.MAX_COMPLETION_TOKENS > Reasoning.BUDGET_TOKENS)
    }
}
