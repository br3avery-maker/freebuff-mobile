package com.freebuff.core.data.repository

import com.freebuff.core.data.db.FreebuffDao
import com.freebuff.core.data.db.MessageEntity
import com.freebuff.core.data.db.SessionEntity
import com.freebuff.core.data.db.SessionWithMessages
import com.freebuff.core.data.db.toDomain
import com.freebuff.core.data.db.toEntity
import com.freebuff.core.model.ChatMsg
import com.freebuff.core.model.Session
import com.freebuff.core.model.uid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 会话与消息仓库:Room 持久化,支持创建/追加/删除/清空。 */
@Singleton
class SessionRepository @Inject constructor(
    private val dao: FreebuffDao,
) {
    val sessions: Flow<List<Session>> = dao.observeSessionsWithMessages()
        .map { list -> list.map { it.toDomain() } }
        .distinctUntilChanged()

    suspend fun get(id: String): Session? = dao.sessionWithMessages(id)?.toDomain()

    /** 创建空会话,返回带 id 的 Session。 */
    suspend fun create(title: String): Session {
        val id = uid()
        val maxSort = dao.allSessions().maxOfOrNull { it.sort } ?: 0L
        dao.upsertSession(SessionEntity(id, title, "刚刚", "", maxSort + 1))
        return Session(id, title, "刚刚", "", emptyList())
    }

    /** 追加消息并刷新会话预览。返回更新后的会话。 */
    suspend fun appendMessages(sessionId: String, messages: List<ChatMsg>): Session? {
        val cur = dao.session(sessionId) ?: return null
        val existing = dao.sessionWithMessages(sessionId)?.messages.orEmpty().size
        val entities = messages.mapIndexed { i, m -> m.toEntity(sessionId, (existing + i).toLong()) }
        dao.upsertMessages(entities)
        val lastText = messages.lastOrNull { it.role == "user" }?.text.orEmpty()
        val preview = if (lastText.isNotBlank()) lastText.take(30) + (if (lastText.length > 30) "…" else "") else cur.preview
        dao.upsertSession(cur.copy(preview = preview))
        return dao.sessionWithMessages(sessionId)?.toDomain()
    }

    /** 替换某条消息(流式回复写入)。 */
    suspend fun replaceMessage(sessionId: String, messageId: String, updated: ChatMsg) {
        val entities = dao.sessionWithMessages(sessionId)?.messages.orEmpty()
        entities.firstOrNull { it.id == messageId }?.let { old ->
            dao.upsertMessages(listOf(updated.toEntity(sessionId, old.sort)))
        }
    }

    suspend fun updateTitle(sessionId: String, title: String) {
        dao.session(sessionId)?.let { dao.upsertSession(it.copy(title = title)) }
    }

    suspend fun delete(sessionId: String) {
        dao.deleteMessages(sessionId)
        dao.deleteSession(sessionId)
    }

    suspend fun clearAll() {
        dao.clearMessages()
        dao.clearSessions()
    }
}
