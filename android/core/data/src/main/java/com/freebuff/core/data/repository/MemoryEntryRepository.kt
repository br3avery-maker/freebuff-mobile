package com.freebuff.core.data.repository

import com.freebuff.core.data.db.FreebuffDao
import com.freebuff.core.data.db.toDomain
import com.freebuff.core.data.db.toEntity
import com.freebuff.core.model.MemoryEntry
import com.freebuff.core.model.MemoryExtraction
import com.freebuff.core.model.MemoryRetrieval
import com.freebuff.core.model.MemoryStore
import com.freebuff.core.model.MemoryType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 检索式记忆库的持久化 + 检索层(与 [MemoryRepository] 的常驻块互补)。
 *
 * - 存储:长/短期条目按 user_id 隔离;写入去重(内容相同只刷新时间与类型)
 * - 检索:词法相关度 Top K([MemoryRetrieval]),命中累加热度,可选类型过滤
 * - 容量:单用户超过 [MemoryStore.PRUNE_KEEP] 条时淘汰最旧(短期优先)
 */
@Singleton
class MemoryEntryRepository @Inject constructor(
    private val dao: FreebuffDao,
) {
    /** 观察全部条目(设置页/调试展示用)。 */
    val entries: Flow<List<MemoryEntry>> =
        dao.observeMemoryEntries().map { list -> list.map { it.toDomain() } }

    suspend fun count(userId: String = MemoryStore.LOCAL_USER_ID): Int = dao.memoryEntries(userId).size

    /**
     * 写入一条记忆。
     * @return 落库后的条目;内容过短(无效)返回 null
     */
    suspend fun add(
        content: String,
        type: String = MemoryType.LONG_TERM,
        userId: String = MemoryStore.LOCAL_USER_ID,
        sessionId: String = "",
        now: Long = System.currentTimeMillis(),
    ): MemoryEntry? {
        val text = normalize(content)
        if (text.length < 2) return null
        val existing = dao.memoryEntries(userId)
        val dup = existing.firstOrNull { normalize(it.content) == text }
            ?: existing.firstOrNull { it.type == type && similar(it.content, text) }
        if (dup != null) {
            // 重复记忆:只刷新时间/类型(避免同一偏好被反复追加)
            val touched = dup.copy(type = type, updatedAt = now, sessionId = sessionId.ifBlank { dup.sessionId })
            dao.upsertMemoryEntry(touched)
            return touched.toDomain()
        }
        val fresh = MemoryEntry(
            userId = userId,
            type = type,
            content = text,
            sessionId = sessionId,
            createdAt = now,
            updatedAt = now,
        )
        val id = dao.upsertMemoryEntry(fresh.toEntity())
        prune(userId)
        return fresh.copy(id = id)
    }

    /** 批量写入提取结果(轮次结束的自动提取路径)。 */
    suspend fun addExtracted(
        items: List<MemoryExtraction.Item>,
        sessionId: String,
        userId: String = MemoryStore.LOCAL_USER_ID,
        now: Long = System.currentTimeMillis(),
    ): Int {
        var stored = 0
        items.forEach { item -> if (add(item.content, item.type, userId, sessionId, now) != null) stored++ }
        return stored
    }

    /**
     * 相关度检索。
     * @param bumpHits 是否累加命中热度(自动注入为 false,避免每轮都改写数据库)
     */
    suspend fun search(
        query: String,
        topK: Int = MemoryStore.DEFAULT_TOP_K,
        memoryType: String? = null,
        userId: String = MemoryStore.LOCAL_USER_ID,
        now: Long = System.currentTimeMillis(),
        bumpHits: Boolean = true,
    ): List<MemoryEntry> {
        val candidates = dao.memoryEntries(userId)
            .filter { memoryType == null || it.type == memoryType }
            .map { it.toDomain() }
        val ranked = MemoryRetrieval.rank(query, candidates, topK, now)
        if (!bumpHits || ranked.isEmpty()) return ranked
        // 热度累加后返回最新状态(调用方看到的 hits 与实际落库一致)
        val bumped = ranked.map { e -> e.copy(hits = e.hits + 1) }
        bumped.forEach { dao.upsertMemoryEntry(it.toEntity()) }
        return bumped
    }

    suspend fun clear() = dao.clearMemoryEntries()

    suspend fun delete(id: Long) = dao.deleteMemoryEntry(id)

    /** 超量淘汰:保留最新 [MemoryStore.PRUNE_KEEP] 条,短期记忆优先出局。 */
    private suspend fun prune(userId: String) {
        val all = dao.memoryEntries(userId)
        if (all.size <= MemoryStore.PRUNE_KEEP) return
        val victims = all
            .sortedWith(compareBy({ if (it.type == MemoryType.SHORT_TERM) 0 else 1 }, { it.updatedAt }))
            .take(all.size - MemoryStore.PRUNE_KEEP)
        victims.forEach { dao.deleteMemoryEntry(it.id) }
    }

    private fun normalize(s: String): String =
        s.trim().replace(Regex("\\s+"), " ").take(MemoryStore.MAX_ENTRY_CHARS)

    /**
     * 近似重复判定(同类型):token 集合重合度 ≥ 0.8 视为同一记忆的换句话表述,
     * 只刷新时间而不新增条目——否则「用户偏好简洁」之类会每轮记一条。
     */
    private fun similar(a: String, b: String): Boolean {
        val ta = MemoryRetrieval.tokenize(a).toSet()
        val tb = MemoryRetrieval.tokenize(b).toSet()
        if (ta.isEmpty() || tb.isEmpty()) return false
        val overlap = ta.count { it in tb }
        return overlap.toDouble() / minOf(ta.size, tb.size) >= 0.8
    }
}
