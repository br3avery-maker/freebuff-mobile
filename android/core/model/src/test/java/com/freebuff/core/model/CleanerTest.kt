package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 清洗修复逻辑:丢弃脏条目、补全字段、过滤快照,并产出修复报告。 */
class CleanerTest {

    private fun model(id: String = "m1", name: String = "MyModel", apiId: String = "api-1",
                      base: String = "https://x.com/v1", models: List<String> = listOf("a", "b")) =
        CustomModel(id = id, name = name, apiId = apiId, base = base, models = models)

    @Test
    fun `空列表原样返回零修复`() {
        val (out, rep) = sanitizeCustomModels(emptyList())
        assertTrue(out.isEmpty())
        assertEquals(0, rep.fixed)
        assertEquals(0, rep.rawN)
        assertEquals(0, rep.nowN)
    }

    @Test
    fun `丢弃 null 条目并计数`() {
        val (out, rep) = sanitizeCustomModels(listOf(null, model()))
        assertEquals(1, out.size)
        assertEquals(1, rep.fixed)
        assertEquals(2, rep.rawN)
        assertEquals(1, rep.nowN)
        assertEquals("drop", rep.items[0].fixes[0].code)
    }

    @Test
    fun `丢弃 id 为空的条目`() {
        val (out, rep) = sanitizeCustomModels(listOf(model(id = "  ")))
        assertTrue(out.isEmpty())
        assertEquals(1, rep.fixed)
        assertEquals("drop", rep.items[0].fixes[0].code)
    }

    @Test
    fun `名称为空补全未命名模型`() {
        val (out, rep) = sanitizeCustomModels(listOf(model(name = "  ")))
        assertEquals("未命名模型", out[0].name)
        assertEquals(1, rep.fixed)
        assertTrue(rep.items[0].fixes.any { it.code == "name" })
    }

    @Test
    fun `空 apiId 或 base 记录为 field 修复`() {
        val (out, rep) = sanitizeCustomModels(listOf(model(apiId = " ", base = "")))
        assertEquals("", out[0].apiId)
        assertEquals("", out[0].base)
        assertEquals(1, rep.fixed)
        val codes = rep.items[0].fixes.map { it.code }
        assertTrue(codes.contains("field"))
    }

    @Test
    fun `过滤空白模型快照并记录数量`() {
        val dirty = listOf("keep", "  ", "", "also-keep")
        val (out, rep) = sanitizeCustomModels(listOf(model(models = dirty)))
        assertEquals(listOf("keep", "also-keep"), out[0].models)
        assertEquals(1, rep.fixed)
        val modelsFix = rep.items[0].fixes.first { it.code == "models" }
        assertEquals(2, modelsFix.n)
    }

    @Test
    fun `干净数据不触发修复`() {
        val (out, rep) = sanitizeCustomModels(listOf(model(), model(id = "m2", name = "B")))
        assertEquals(2, out.size)
        assertEquals(0, rep.fixed)
        assertTrue(rep.items.isEmpty())
    }

    @Test
    fun `修复报告保留原始与修复后对象`() {
        val raw = model(name = "")
        val (out, rep) = sanitizeCustomModels(listOf(raw))
        val item = rep.items[0]
        assertEquals(raw, item.raw)
        assertEquals(out[0], item.fixed)
    }
}
