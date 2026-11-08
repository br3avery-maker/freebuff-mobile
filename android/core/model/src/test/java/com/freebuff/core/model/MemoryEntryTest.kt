package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检索式记忆:分词、相关度排序、memory_recall 参数解析与结果格式、提取结果解析。 */
class MemoryEntryTest {

    private val day = 86_400_000L

    private fun entry(
        text: String,
        type: String = MemoryType.LONG_TERM,
        at: Long = 1_700_000_000_000,
        hits: Int = 0,
    ) = MemoryEntry(id = 0, type = type, content = text, createdAt = at, updatedAt = at, hits = hits)

    @Test
    fun `English lookup status does not become memory`() {
        assertTrue(MemoryExtraction.parse("""[{"content":"No memories related to this request"},{"content":"Called memory_recall with no results"}]""").isEmpty())
        assertEquals("The user prefers short answers", MemoryExtraction.parse("""[{"content":"The user prefers short answers"}]""").single().content)
    }

    @Test
    fun `分词 CJK 切 bigram 拉丁按词小写`() {
        val tokens = MemoryRetrieval.tokenize("我喜欢 Kotlin 简洁")
        assertTrue("应含 bigram 我喜", tokens.contains("我喜"))
        assertTrue("应含 bigram 喜欢", tokens.contains("喜欢"))
        assertTrue("应含整串特征 我喜欢", tokens.contains("我喜欢"))
        assertTrue("拉丁词应小写", tokens.contains("kotlin"))
        assertTrue("单词不重复为 bigram+整串", tokens.count { it == "简洁" } == 1)
        assertTrue(MemoryRetrieval.tokenize(",!?").isEmpty())
    }

    @Test
    fun `相关度排序 命中偏好条目优先`() {
        val entries = listOf(
            entry("用户偏好:喜欢简洁的回复,不要长段落"),
            entry("用户使用的是 Android 模拟器"),
            entry("项目进度:记忆系统已实现"),
        )
        val ranked = MemoryRetrieval.rank("用户喜欢什么样的回复风格", entries, topK = 3, now = 1_700_000_000_000L)
        assertEquals("命中偏好条目应排第一", entries[0], ranked.first())
    }

    @Test
    fun `查询无特征时退化为最新优先`() {
        val now = 1_700_000_000_000L
        val old = entry("旧记忆", at = now - 30 * day)
        val fresh = entry("新记忆", at = now - 1 * day)
        val ranked = MemoryRetrieval.rank("?!", listOf(old, fresh), topK = 2, now = now)
        assertEquals(fresh, ranked.first())
    }

    @Test
    fun `top_k 超出候选数时返回全部 命中热度加权`() {
        val now = 1_700_000_000_000L
        val warm = entry("用户偏好简洁", at = now, hits = 9)
        val cold = entry("用户偏好简洁", at = now, hits = 0)
        val ranked = MemoryRetrieval.rank("用户偏好", listOf(cold, warm), topK = 5, now = now)
        assertEquals(2, ranked.size)
        assertEquals("命中次数高者优先", warm, ranked.first())
        assertTrue(MemoryRetrieval.rank("用户偏好", listOf(cold), topK = 5, now = now).size == 1)
    }

    @Test
    fun `短期记忆时间衰减快于长期`() {
        val now = 1_700_000_000_000L
        val at = now - 10 * day
        val longTerm = entry("任务上下文", type = MemoryType.LONG_TERM, at = at)
        val shortTerm = entry("任务上下文", type = MemoryType.SHORT_TERM, at = at)
        val longScore = MemoryRetrieval.score("任务上下文", longTerm, now)
        val shortScore = MemoryRetrieval.score("任务上下文", shortTerm, now)
        assertTrue("短期记忆衰减更快:long=$longScore short=$shortScore", longScore > shortScore)
    }

    @Test
    fun `类型归一化与标签`() {
        assertEquals(MemoryType.LONG_TERM, MemoryType.normalize("long"))
        assertEquals(MemoryType.LONG_TERM, MemoryType.normalize("长期"))
        assertEquals(MemoryType.SHORT_TERM, MemoryType.normalize("Short_Term"))
        assertEquals(MemoryType.SHORT_TERM, MemoryType.normalize("短期"))
        assertNull(MemoryType.normalize("unknown"))
        assertNull(MemoryType.normalize(null))
        assertEquals("Long-term", MemoryType.label(MemoryType.LONG_TERM))
        assertEquals("Short-term", MemoryType.label(MemoryType.SHORT_TERM))
    }

    @Test
    fun `memory_recall 参数解析 默认条数与上限`() {
        val a = MemoryRecallCodec.parse("""{"user_id":"local","query":"偏好"}""")
        assertEquals("local", a.userId)
        assertEquals("偏好", a.query)
        assertEquals(MemoryStore.DEFAULT_TOP_K, a.topK)
        assertNull(a.memoryType)

        val b = MemoryRecallCodec.parse("""{"query":"x","top_k":999,"memory_type":"长期"}""")
        assertEquals(MemoryStore.MAX_TOP_K, b.topK)
        assertEquals(MemoryType.LONG_TERM, b.memoryType)
        assertEquals("缺 user_id 时应为空串(由调用方判错)", "", b.userId)

        val c = MemoryRecallCodec.parse("""{"query":"x","top_k":0,"memory_type":"short"}""")
        assertEquals(1, c.topK)
        assertEquals(MemoryType.SHORT_TERM, c.memoryType)

        assertEquals(MemoryStore.DEFAULT_TOP_K, MemoryRecallCodec.parse("not json").topK)
    }

    @Test
    fun `结果与注入格式`() {
        val e1 = entry("用户偏好简洁的回复", type = MemoryType.LONG_TERM)
        val e2 = entry("进行中:记忆检索", type = MemoryType.SHORT_TERM)
        val prompt = MemoryRecallCodec.formatForPrompt(listOf(e1, e2))
        assertEquals("1. [Long-term] 用户偏好简洁的回复\n2. [Short-term] 进行中:记忆检索", prompt)

        val result = MemoryRecallCodec.formatResult("local", "偏好", listOf(e1))
        assertTrue(result.contains("Found 1 related memories"))
        assertTrue(result.contains("user_id=local"))
        assertTrue(result.contains("[Long-term]"))

        assertTrue(MemoryRecallCodec.formatResult("local", "天气", emptyList()).contains("No memories"))
    }

    @Test
    fun `提取解析 兼容围栏与夹带说明`() {
        val raw = "好的,结果如下:\n```json\n[{\"type\":\"long_term\",\"content\":\"用户偏好简洁回复\"},{\"type\":\"short\",\"content\":\"正在排查登录问题\"}]\n```\n以上。"
        val items = MemoryExtraction.parse(raw)
        assertEquals(2, items.size)
        assertEquals(MemoryType.LONG_TERM, items[0].type)
        assertEquals("用户偏好简洁回复", items[0].content)
        assertEquals("short 也应归一化为 short_term", MemoryType.SHORT_TERM, items[1].type)
    }

    @Test
    fun `提取解析 纯字符串元素与去重截断`() {
        val raw = """["用户在做 Android 项目","用户在做 Android 项目", "", {"type":"bogus","content":"无效类型按长期处理"}]"""
        val items = MemoryExtraction.parse(raw)
        assertEquals(2, items.size)
        assertEquals(MemoryType.LONG_TERM, items[0].type)
        assertEquals("无效类型按长期处理", items[1].content)
        assertEquals(MemoryType.LONG_TERM, items[1].type)
    }

    @Test
    fun `提取解析 丢弃无记录类空转叙述`() {
        val raw = """[{"type":"long_term","content":"项目代号为空。"},{"type":"long_term","content":"已调用检索工具确认代号"},{"type":"long_term","content":"用户偏好简洁回复"},{"type":"short_term","content":"记忆库中没有相关条目"}]"""
        val items = MemoryExtraction.parse(raw)
        assertEquals(1, items.size)
        assertEquals("用户偏好简洁回复", items.first().content)
    }

    @Test
    fun `提取解析 空数组与非法输入返回空`() {
        assertTrue(MemoryExtraction.parse("[]").isEmpty())
        assertTrue(MemoryExtraction.parse("我不记得有什么需要保存的。").isEmpty())
        assertTrue(MemoryExtraction.parse("[{").isEmpty())
    }

    @Test
    fun `提取解析 单轮最多 5 条 单条截断`() {
        val many = (1..8).joinToString(",") { "{\"type\":\"long_term\",\"content\":\"记忆条目 $it\"}" }
        assertEquals(MemoryExtraction.MAX_ITEMS, MemoryExtraction.parse("[$many]").size)
        val long = "长".repeat(1000)
        assertEquals(
            MemoryExtraction.MAX_CONTENT_CHARS,
            MemoryExtraction.parse("[{\"type\":\"long_term\",\"content\":\"$long\"}]").first().content.length,
        )
    }
}
