package com.freebuff.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Letta/MemGPT 式核心记忆块(core memory blocks)。
 *
 * 设计借鉴 Letta 公开架构:记忆以「命名块」形式常驻 system prompt(如 persona / human / project),
 * 模型可通过 save_memory 工具自编辑块内容——记忆由 agent 主动管理,而非固定模板。
 * 块持久化在 Room settings 表(JSON 序列化),跨会话共享。
 */
data class MemoryBlock(
    val name: String,
    val content: String,
    /** 块的 token 预算上限(超出时触发压缩,防止记忆挤占对话空间)。 */
    val charLimit: Int = DEFAULT_CHAR_LIMIT,
) {
    companion object {
        const val DEFAULT_CHAR_LIMIT = 600

        /** 默认块集:身份 + 用户画像 + 任务焦点(Letta 的 persona/human + Cline Focus Chain 思想)。 */
        fun defaults(): List<MemoryBlock> = listOf(
            MemoryBlock("persona", "You are the Freebuff assistant running on the user's mobile device. Use tools to obtain current information. Respond in the user's language; default to English."),
            MemoryBlock("user", "(No user memories yet)"),
            MemoryBlock("project", "(Current task focus and progress; updated by the assistant as needed)"),
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("content", content)
        .put("charLimit", charLimit)
}

object MemoryCodec {
    fun blocksToJson(blocks: List<MemoryBlock>): String {
        val arr = JSONArray()
        blocks.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    fun blocksFromJson(s: String): List<MemoryBlock> {
        if (s.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim()
                if (name.isEmpty()) return@mapNotNull null
                MemoryBlock(
                    name = name,
                    content = o.optString("content"),
                    charLimit = o.optInt("charLimit", MemoryBlock.DEFAULT_CHAR_LIMIT),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 块集序列化为 system prompt 片段(Letta core memory 视图格式)。 */
    fun toPrompt(blocks: List<MemoryBlock>): String = blocks.joinToString("\n\n") { b ->
        "<memory_block name=\"${b.name}\">\n${b.content.trim()}\n</memory_block>"
    }

    /**
     * 把块内容压缩到各自 charLimit 内:超限保留头部(记忆块以最新追加为准时截头,
     * 这里按「保留头部 + 标注截断」处理,模型可调用 save_memory 重写精炼版)。
     */
    fun enforceLimits(blocks: List<MemoryBlock>): List<MemoryBlock> = blocks.map { b ->
        if (b.content.length <= b.charLimit) b
        else b.copy(content = b.content.take(b.charLimit) + "\n(Truncated; use save_memory to condense)")
    }

    /** save_memory 工具的参数模型:name 定位块,content 为新内容;replace=false 时追加。 */
    data class SaveArgs(val block: String, val content: String, val replace: Boolean)

    fun parseSaveArgs(argsJson: String): SaveArgs {
        val o = try { JSONObject(argsJson) } catch (e: Exception) { JSONObject() }
        return SaveArgs(
            block = o.optString("block").trim(),
            content = o.optString("content"),
            replace = o.optBoolean("replace", true),
        )
    }
}
