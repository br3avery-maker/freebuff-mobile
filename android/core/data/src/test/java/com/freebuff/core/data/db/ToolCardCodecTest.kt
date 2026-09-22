package com.freebuff.core.data.db

import com.freebuff.core.model.ToolCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具卡片持久化:JsonCodec 编解码往返 + 旧数据兼容。 */
class ToolCardCodecTest {

    @Test
    fun `编解码往返保留全部字段`() {
        val cards = listOf(
            ToolCard(callId = "c1", tool = "read_files", input = "a.ts", output = "ok", state = "done"),
            ToolCard(callId = "c2", tool = "run_terminal_command", input = "ls", output = "", state = "running"),
            ToolCard(callId = "c3", tool = "subagent:thinker", input = "思考中", output = "", state = "error"),
        )
        val round = JsonCodec.toolCardsFromJson(JsonCodec.toolCardsToJson(cards))
        assertEquals(cards, round)
    }

    @Test
    fun `空列表往返为空`() {
        assertTrue(JsonCodec.toolCardsToJson(emptyList()).isEmpty() ||
            JsonCodec.toolCardsFromJson(JsonCodec.toolCardsToJson(emptyList())).isEmpty())
        assertTrue(JsonCodec.toolCardsFromJson("").isEmpty())
        assertTrue(JsonCodec.toolCardsFromJson("   ").isEmpty())
    }

    @Test
    fun `损坏 JSON 返回空列表不崩溃`() {
        assertTrue(JsonCodec.toolCardsFromJson("{broken").isEmpty())
        assertTrue(JsonCodec.toolCardsFromJson("[1,2,3]").isEmpty()) // 非对象条目被跳过
    }

    @Test
    fun `缺失 state 字段默认 done`() {
        val from = JsonCodec.toolCardsFromJson("""[{"callId":"x","tool":"glob"}]""")
        assertEquals(1, from.size)
        assertEquals("done", from[0].state)
        assertTrue(!from[0].isRunning)
    }

    @Test
    fun `消息实体映射保留工具卡片`() {
        val msg = com.freebuff.core.model.ChatMsg(
            id = "m1", role = "agent", text = "done",
            tools = listOf(ToolCard("c1", "code_search", input = "foo", output = "2 hits", state = "done")),
        )
        val entity = msg.toEntity(sessionId = "s1", sort = 0)
        assertEquals(msg.tools, entity.toDomain().tools)
    }

    @Test
    fun `子代理前缀解析`() {
        val card = ToolCard(callId = "s", tool = ToolCard.SUBAGENT_PREFIX + "thinker")
        assertEquals("thinker", card.subagentName)
        assertTrue(!ToolCard(callId = "s", tool = "read_files").let { it.subagentName != null })
    }
}
