package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 官方网关目录整理:已知 id 复用内置元数据,未知 id 自动生成展示信息。 */
class CatalogTest {

    @Test
    fun `已知 id 复用内置元数据`() {
        val models = officialModelsFromIds(listOf("deepseek-v4-flash"))
        assertEquals(1, models.size)
        assertEquals(OFFICIAL_MODELS.first { it.id == "deepseek-v4-flash" }.name, models[0].name)
        assertEquals(OFFICIAL_MODELS.first { it.id == "deepseek-v4-flash" }.badge, models[0].badge)
    }

    @Test
    fun `未知 id 自动生成名称与徽标`() {
        val m = officialModelsFromIds(listOf("qwen2.5-coder-32b"))[0]
        assertEquals("qwen2.5-coder-32b", m.id)
        assertEquals("Qwen2.5 Coder 32b", m.name)
        assertEquals("QWE", m.badge)
        assertEquals("full", m.tier)
        assertTrue(m.desc.isNotBlank())
    }

    @Test
    fun `保持顺序并去重与过滤空值`() {
        val ids = listOf("b-model", "", "a-model", "b-model", "  ")
        val models = officialModelsFromIds(ids)
        assertEquals(listOf("b-model", "a-model"), models.map { it.id })
    }

    @Test
    fun `内置目录作为回退且非空`() {
        assertTrue(builtinOfficialModels.isNotEmpty())
        assertEquals(OFFICIAL_MODELS, builtinOfficialModels)
    }

    @Test
    fun `prettifyModelId 处理缩写与数字段`() {
        assertEquals("GPT 5.6 Luna", prettifyModelId("gpt-5.6-luna"))
        assertEquals("GLM 5.3 Flash", prettifyModelId("glm-5.3-flash"))
        assertEquals("Deepseek V4 Flash", prettifyModelId("deepseek-v4-flash"))
        assertEquals("Mimo 2.5", prettifyModelId("mimo-2.5"))
        assertEquals("Model", prettifyModelId("model"))
    }

    @Test
    fun `badgeOf 取字母数字前缀且最多三位`() {
        assertEquals("DEE", badgeOf("deepseek-v4-flash"))
        assertEquals("AI", badgeOf("---"))
        assertEquals("G5", badgeOf("g-5"))
    }
}
