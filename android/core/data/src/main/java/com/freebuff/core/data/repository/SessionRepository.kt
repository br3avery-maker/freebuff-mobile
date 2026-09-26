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

/**
 * 被删除会话的快照。除会话内容外还带着原始列表位次 [sort],
 * 让「撤销删除」把会话放回原位,而不是当作新会话顶到最前。
 */
data class DeletedSession(val session: Session, val sort: Long)

/** 会话与消息仓库:Room 持久化,支持创建/追加/删除(可撤销)/清空。 */
@Singleton
class SessionRepository @Inject constructor(
    private val dao: FreebuffDao,
) {
    val sessions: Flow<List<Session>> = dao.observeSessionsWithMessages()
        .map { list -> list.map { it.toDomain() } }
        .distinctUntilChanged()

    suspend fun get(id: String): Session? = dao.sessionWithMessages(id)?.toDomain()

    /** 创建空会话,返回带 id 的 Session(记下创建时刻,列表按它分时间组)。 */
    suspend fun create(title: String): Session {
        val id = uid()
        val maxSort = dao.allSessions().maxOfOrNull { it.sort } ?: 0L
        val now = System.currentTimeMillis()
        dao.upsertSession(SessionEntity(id, title, "刚刚", "", maxSort + 1, now))
        return Session(id, title, "刚刚", "", emptyList(), now)
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

    /**
     * 删除会话(连带消息),返回删除前的快照供「撤销」使用。
     * 会话不存在时返回 null —— 调用方据此不展示空撤销条。
     */
    suspend fun deleteWithSnapshot(sessionId: String): DeletedSession? {
        val row = dao.session(sessionId) ?: return null
        val session = dao.sessionWithMessages(sessionId)?.toDomain() ?: return null
        dao.deleteMessages(sessionId)
        dao.deleteSession(sessionId)
        return DeletedSession(session, row.sort)
    }

    /**
     * 恢复被删除的会话:沿用原 [DeletedSession.sort] 回到列表原位,
     * 消息按 0..n 重写序号,内容与删除前一致。
     */
    suspend fun restore(deleted: DeletedSession) {
        val s = deleted.session
        dao.upsertSession(s.toEntity(deleted.sort))
        val entities = s.messages.mapIndexed { i, m -> m.toEntity(s.id, i.toLong()) }
        if (entities.isNotEmpty()) dao.upsertMessages(entities)
    }

    suspend fun clearAll() {
        dao.clearMessages()
        dao.clearSessions()
    }
}
