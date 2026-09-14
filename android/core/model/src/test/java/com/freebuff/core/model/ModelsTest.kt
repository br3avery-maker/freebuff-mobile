package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型目录合并与远程版本比较。 */
class ModelsTest {

    @Test
    fun `官方模型目录非空且字段齐全`() {
        assertTrue(OFFICIAL_MODELS.isNotEmpty())
        assertEquals("full", OFFICIAL_MODELS.first().tier)
        assertEquals("deepseek-v4-flash", OFFICIAL_MODELS.first().id)
    }

    @Test
    fun `自定义模型映射为 custom 条目`() {
        val list = listOf(CustomModel(id = "cm1", name = "本地端点", base = "https://local/v1"))
        val official = list.asOfficialModels()
        assertEquals(1, official.size)
        val m = official[0]
        assertEquals("cm1", m.id)
        assertEquals("custom", m.tier)
        assertEquals("自定义模型", m.tierText)
        assertEquals("M", m.badge)
        assertEquals("https://local/v1", m.desc)
    }

    @Test
    fun `合并列表官方在前自定义在后`() {
        val customs = listOf(CustomModel(id = "cm1", name = "A"))
        val merged = mergedModelList(customs)
        assertEquals(OFFICIAL_MODELS.size + 1, merged.size)
        assertEquals(OFFICIAL_MODELS.first().id, merged.first().id)
        assertEquals("cm1", merged.last().id)
        assertEquals("custom", merged.last().tier)
    }

    @Test
    fun `空自定义列表只含官方`() {
        val merged = mergedModelList(emptyList())
        assertEquals(OFFICIAL_MODELS.size, merged.size)
    }

    // ---------- RemoteVersion.isNewerThan ----------
    @Test
    fun `远端版本更高时提示更新`() {
        assertTrue(RemoteVersion(version = "0.3.0").isNewerThan("0.2.0"))
        assertTrue(RemoteVersion(version = "v0.3.0").isNewerThan("0.2.0"))
    }

    @Test
    fun `远端版本相同或更低时不提示`() {
        assertFalse(RemoteVersion(version = "0.2.0").isNewerThan("0.2.0"))
        assertFalse(RemoteVersion(version = "0.1.9").isNewerThan("0.2.0"))
        assertFalse(RemoteVersion(version = "").isNewerThan("0.2.0"))
    }

    @Test
    fun `不同位数版本号比较`() {
        assertTrue(RemoteVersion(version = "1.0").isNewerThan("0.9.9"))
        assertTrue(RemoteVersion(version = "0.10.0").isNewerThan("0.9.0"))
        assertFalse(RemoteVersion(version = "0.2").isNewerThan("0.2.0"))
    }

    @Test
    fun `LATEST_VERSION 高于默认版本`() {
        assertTrue(RemoteVersion(version = LATEST_VERSION).isNewerThan("0.1.0"))
    }
}
