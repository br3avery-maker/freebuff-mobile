package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Letta 式记忆块:编解码、prompt 渲染、限额压缩、save_memory 参数解析。 */
class MemoryTest {

    @Test
    fun `默认块集包含 persona user project`() {
        val blocks = MemoryBlock.defaults()
        assertEquals(listOf("persona", "user", "project"), blocks.map { it.name })
    }

    @Test
    fun `JSON 往返无损`() {
        val blocks = listOf(
            MemoryBlock("persona", "测试助手", 100),
            MemoryBlock("user", "喜欢 Kotlin"),
        )
        val back = MemoryCodec.blocksFromJson(MemoryCodec.blocksToJson(blocks))
        assertEquals(blocks, back)
    }

    @Test
    fun `损坏 JSON 返回空列表`() {
        assertEquals(emptyList<MemoryBlock>(), MemoryCodec.blocksFromJson("not-json"))
        assertEquals(emptyList<MemoryBlock>(), MemoryCodec.blocksFromJson(""))
    }

    @Test
    fun `toPrompt 渲染为命名块格式`() {
        val prompt = MemoryCodec.toPrompt(listOf(MemoryBlock("user", "偏好中文回复")))
        assertTrue(prompt.contains("<memory_block name=\"user\">"))
        assertTrue(prompt.contains("偏好中文回复"))
        assertTrue(prompt.contains("</memory_block>"))
    }

    @Test
    fun `enforceLimits 超限截断并标注`() {
        val blocks = listOf(MemoryBlock("user", "a".repeat(1000), charLimit = 100))
        val limited = MemoryCodec.enforceLimits(blocks)
        assertTrue(limited[0].content.length <= 100 + 30) // 截断 + 标注
        assertTrue(limited[0].content.contains("save_memory"))
    }

    @Test
    fun `parseSaveArgs 解析三个参数`() {
        val a = MemoryCodec.parseSaveArgs("""{"block":"user","content":"喜欢简洁","replace":false}""")
        assertEquals("user", a.block)
        assertEquals("喜欢简洁", a.content)
        assertEquals(false, a.replace)
    }

    @Test
    fun `parseSaveArgs 坏 JSON 安全回退`() {
        val a = MemoryCodec.parseSaveArgs("{{{")
        assertEquals("", a.block)
        assertEquals("", a.content)
        assertEquals(true, a.replace)
    }

    @Test
    fun `save_memory 工具已注册且参数齐全`() {
        val t = DefaultTools.ALL.first { it.name == "save_memory" }
        assertEquals(listOf("block", "content", "replace"), t.params.map { p -> p.name })
        assertTrue(t.description.contains("persona"))
    }

    @Test
    fun `forCapabilities 关闭记忆时移除 save_memory`() {
        assertTrue(DefaultTools.forCapabilities(memory = true).any { it.name == "save_memory" })
        assertTrue(DefaultTools.forCapabilities(memory = false).none { it.name == "save_memory" })
        assertTrue(DefaultTools.forCapabilities(memory = false).any { it.name == "web_search" })
    }
}
