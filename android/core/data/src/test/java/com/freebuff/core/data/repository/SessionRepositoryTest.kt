package com.freebuff.core.data.repository

import com.freebuff.core.data.db.CustomModelEntity
import com.freebuff.core.data.db.FreebuffDao
import com.freebuff.core.data.db.MemoryEntity
import com.freebuff.core.data.db.MemoryEntryEntity
import com.freebuff.core.data.db.MessageEntity
import com.freebuff.core.data.db.SessionEntity
import com.freebuff.core.data.db.SessionWithMessages
import com.freebuff.core.data.db.SettingEntity
import com.freebuff.core.model.ChatMsg
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话删除与撤销恢复:级联、位次、消息顺序必须前后对称。 */
class SessionRepositoryTest {

    private val dao = MemDao()
    private val repo = SessionRepository(dao)

    @Test
    fun `创建会话写入创建时刻与初始位次`() = runTest {
        val s = repo.create("新会话")
        assertTrue("创建时刻必须落库(列表按它分组)", s.createdAt > 0L)
        assertEquals(s.createdAt, dao.sessions[s.id]?.createdAt)
        assertEquals("刚刚", s.time)
        assertEquals(1L, dao.sessions[s.id]?.sort)
    }

    @Test
    fun `删除会话连带清掉消息`() = runTest {
        val s = repo.create("A")
        repo.appendMessages(s.id, listOf(ChatMsg("m1", "user", text = "hi")))
        val snapshot = repo.deleteWithSnapshot(s.id)
        assertNotNull(snapshot)
        assertEquals(s.id, snapshot!!.session.id)
        assertNull("会话行应已删除", dao.sessions[s.id])
        assertTrue("消息必须一起清掉,否则残留孤儿数据", dao.messages[s.id].orEmpty().isEmpty())
    }

    @Test
    fun `删除不存在的会话返回空快照`() = runTest {
        assertNull(repo.deleteWithSnapshot("no-such-session"))
    }

    @Test
    fun `撤销恢复沿用原位次与消息顺序`() = runTest {
        val a = repo.create("A")
        val b = repo.create("B")
        repo.appendMessages(
            a.id,
            listOf(ChatMsg("m1", "user", text = "一"), ChatMsg("m2", "agent", text = "二")),
        )
        val sortA = dao.sessions[a.id]!!.sort
        val sortB = dao.sessions[b.id]!!.sort

        val snapshot = repo.deleteWithSnapshot(a.id)!!
        // 删除期间新建的会话应当排在更前,恢复不能把 A 顶到最上面
        val c = repo.create("C")
        assertTrue(dao.sessions[c.id]!!.sort > sortA)

        repo.restore(snapshot)
        assertEquals("恢复必须回到原列表位次", sortA, dao.sessions[a.id]!!.sort)
        assertEquals(3, dao.sessions.size)   // A 恢复、B 原位、C 保留
        assertEquals(listOf("m1", "m2"), dao.messages[a.id]!!.map { it.id })
        assertEquals(listOf("一", "二"), repo.get(a.id)!!.messages.map { it.text })
        assertEquals(sortB, dao.sessions[b.id]!!.sort)
    }

    @Test
    fun `恢复后撤销快照里的创建时刻不变`() = runTest {
        val s = repo.create("A")
        val snapshot = repo.deleteWithSnapshot(s.id)!!
        repo.restore(snapshot)
        assertEquals(s.createdAt, dao.sessions[s.id]?.createdAt)
        assertEquals(s.createdAt, snapshot.session.createdAt)
    }

    @Test
    fun `无消息的会话删除后可原样恢复`() = runTest {
        val s = repo.create("空会话")
        val snapshot = repo.deleteWithSnapshot(s.id)!!
        repo.restore(snapshot)
        assertEquals(0, repo.get(s.id)!!.messages.size)
        assertEquals("空会话", dao.sessions[s.id]?.title)
    }

    /* ---------------- 全内存 DAO(会话/消息真实读写,其余无需关心) ---------------- */

    private class MemDao : FreebuffDao {
        val sessions = LinkedHashMap<String, SessionEntity>()
        val messages = LinkedHashMap<String, List<MessageEntity>>()

        override fun observeSessionsWithMessages(): Flow<List<SessionWithMessages>> =
            flowOf(sessions.values.map { SessionWithMessages(it, messages[it.id].orEmpty()) })

        override suspend fun sessionWithMessages(id: String): SessionWithMessages? {
            val s = sessions[id] ?: return null
            return SessionWithMessages(s, messages[id].orEmpty().sortedBy { it.sort })
        }

        override suspend fun allSessions(): List<SessionEntity> = sessions.values.toList()
        override suspend fun session(id: String): SessionEntity? = sessions[id]
        override suspend fun upsertSession(session: SessionEntity) { sessions[session.id] = session }
        override suspend fun deleteSession(id: String) { sessions.remove(id) }
        override suspend fun clearSessions() { sessions.clear() }

        override fun observeMessages(sessionId: String): Flow<List<MessageEntity>> =
            flowOf(messages[sessionId].orEmpty())

        override suspend fun upsertMessages(messages: List<MessageEntity>) {
            messages.groupBy { it.sessionId }.forEach { (sid, incoming) ->
                val kept = this.messages[sid].orEmpty().filter { old -> incoming.none { it.id == old.id } }
                this.messages[sid] = (kept + incoming).sortedBy { it.sort }
            }
        }

        override suspend fun deleteMessages(sessionId: String) { messages.remove(sessionId) }
        override suspend fun clearMessages() { messages.clear() }

        override fun observeCustomModels(): Flow<List<CustomModelEntity>> = flowOf(emptyList())
        override suspend fun customModel(id: String): CustomModelEntity? = null
        override suspend fun allCustomModels(): List<CustomModelEntity> = emptyList()
        override suspend fun upsertCustomModel(model: CustomModelEntity) = Unit
        override suspend fun deleteCustomModel(id: String) = Unit
        override suspend fun clearCustomModels() = Unit

        override suspend fun allSettings(): List<SettingEntity> = emptyList()
        override suspend fun setting(key: String): SettingEntity? = null
        override fun observeSetting(key: String): Flow<SettingEntity?> = flowOf(null)
        override suspend fun upsertSetting(setting: SettingEntity) = Unit
        override suspend fun deleteSetting(key: String) = Unit
        override suspend fun clearSettings() = Unit

        override suspend fun allMemories(): List<MemoryEntity> = emptyList()
        override fun observeMemories(): Flow<List<MemoryEntity>> = flowOf(emptyList())
        override suspend fun upsertMemory(memory: MemoryEntity) = Unit
        override suspend fun deleteMemory(block: String) = Unit
        override suspend fun clearMemories() = Unit

        override suspend fun memoryEntries(userId: String): List<MemoryEntryEntity> = emptyList()
        override fun observeMemoryEntries(): Flow<List<MemoryEntryEntity>> = flowOf(emptyList())
        override suspend fun upsertMemoryEntry(entry: MemoryEntryEntity): Long = 0L
        override suspend fun deleteMemoryEntry(id: Long) = Unit
        override suspend fun clearMemoryEntries() = Unit
    }
}
