package com.freebuff.core.data.repository

import com.freebuff.core.data.db.FreebuffDao
import com.freebuff.core.data.db.MemoryEntity
import com.freebuff.core.model.MemoryBlock
import com.freebuff.core.model.MemoryCodec
import com.freebuff.core.model.uid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆仓库:Letta/MemGPT 式核心记忆块的持久化层。
 *
 * - 块集存 Room memories 表,跨会话共享、重启不丢
 * - 首次访问自动播种默认块(persona/user/project)
 * - save_memory 工具写入走 [applySave],与模型自编辑共用一套路径
 */
@Singleton
class MemoryRepository @Inject constructor(
    private val dao: FreebuffDao,
) {
    /** 观察全部记忆块(设置页展示/编辑用)。 */
    val blocks: Flow<List<MemoryBlock>> = dao.observeMemories().map { list ->
        list.map { MemoryBlock(it.block, it.content, it.charLimit) }
    }

    /** 读取当前全部块(循环注入用);空表时先播种默认块。 */
    suspend fun load(): List<MemoryBlock> {
        val cur = dao.allMemories()
        if (cur.isNotEmpty()) return cur.map { MemoryBlock(it.block, it.content, it.charLimit) }
        val defaults = MemoryBlock.defaults()
        defaults.forEachIndexed { i, b -> dao.upsertMemory(MemoryEntity(b.name, b.content, b.charLimit, i.toLong())) }
        return defaults
    }

    /**
     * save_memory 工具的落库路径:replace=true 覆盖块内容,false 追加(换行分隔,受 charLimit 压缩)。
     * @return 执行结果描述(回传给模型)
     */
    suspend fun applySave(block: String, content: String, replace: Boolean): String {
        val name = block.trim()
        if (name.isEmpty()) return "Could not save: missing block argument"
        if (content.isBlank()) {
            dao.deleteMemory(name)
            return "Cleared memory block “$name”"
        }
        val cur = dao.allMemories().firstOrNull { it.block == name }
        val newContent = if (replace || cur == null) content
        else (cur.content + "\n" + content).let { c ->
            // 追加超出限额时压缩:保留头部 + 追加段
            val limit = cur.charLimit
            if (c.length > limit) c.take(limit - 60) + "\n(Earlier content condensed)\n" + content else c
        }
        dao.upsertMemory(MemoryEntity(name, newContent, cur?.charLimit ?: MemoryBlock.DEFAULT_CHAR_LIMIT, cur?.sort ?: nextSort()))
        return "Saved to memory block “$name” (${newContent.length} characters)"
    }

    suspend fun setBlock(block: String, content: String) = applySave(block, content, replace = true)

    suspend fun clearAll() = dao.clearMemories()

    private suspend fun nextSort(): Long = (dao.allMemories().maxOfOrNull { it.sort } ?: 0L) + 1
}
