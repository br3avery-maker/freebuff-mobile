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
    fun `空 ID 生成新 ID 并保留记录`() {
        val (out, rep) = sanitizeCustomModels(listOf(model(id = "  ")))
        assertEquals(1, out.size)
        assertTrue(out[0].id.startsWith("cm-"))
        assertEquals(1, rep.fixed)
        assertEquals("id", rep.items[0].fixes[0].code)
        // 差异可见:raw 保留原始空 ID
        val raw = rep.items[0].raw as CustomModel
        assertEquals("  ", raw.id)
        assertEquals(out[0], rep.items[0].fixed)
    }

    @Test
    fun `null 条目记 drop 且 raw 为 null`() {
        val (out, rep) = sanitizeCustomModels(listOf(null, model()))
        assertEquals(1, out.size)
        val item = rep.items.first { it.fixes.any { f -> f.code == "drop" } }
        assertEquals(1, item.i)
        assertEquals(null, item.raw)
        assertEquals(null, item.fixed)
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

    @Test
    fun `混合脏数据统计正确`() {
        val list = listOf<CustomModel?>(null, model(), model(id = ""), model(name = " "))
        val (out, rep) = sanitizeCustomModels(list)
        // null 丢弃,其余 3 条都保留(空 ID 生成后保留)
        assertEquals(3, out.size)
        assertEquals(3, rep.fixed)
        assertEquals(4, rep.rawN)
        assertEquals(3, rep.nowN)
        assertEquals("drop", rep.items[0].fixes[0].code)
        assertTrue(rep.items[1].fixes.any { it.code == "id" })
        assertTrue(rep.items[2].fixes.any { it.code == "name" })
    }

    @Test
    fun `报告 raw 列表包含 null 占位`() {
        val rawList = listOf<CustomModel?>(null, model())
        val (_, rep) = sanitizeCustomModels(rawList)
        assertEquals(2, rep.raw?.size)
        assertEquals(null, rep.raw?.get(0))
        assertEquals(model(), rep.raw?.get(1))
    }
}
