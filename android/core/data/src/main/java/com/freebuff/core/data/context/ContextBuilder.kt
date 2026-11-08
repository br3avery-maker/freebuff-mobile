package com.freebuff.core.data.context

import com.freebuff.core.data.network.ChatMessage
import com.freebuff.core.model.ChatMsg
import com.freebuff.core.model.ContextBudget
import com.freebuff.core.model.ContextPolicy
import com.freebuff.core.model.AgentLoop
import com.freebuff.core.model.ContextStats
import com.freebuff.core.model.MemoryBlock
import com.freebuff.core.model.MemoryCodec
import com.freebuff.core.model.MemoryStore
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** 会话内一条待序列化消息(角色 + 文本 + 内嵌工具卡片)。 */
data class ContextTurn(
    val role: String,
    val text: String,
    val toolOutputs: List<Pair<String, Pair<String, String>>>, // tool name → (state, output)
)

/**
 * 上下文构建器:把「记忆块 + 会话历史」组装成符合 token 预算的请求消息。
 *
 * 三级降级策略(借鉴 Cline auto-compact 与 LibreChat 会话摘要):
 * 1. 工具结果机械压缩——有损但保留边界信息,单条超限才触发
 * 2. 预算裁剪——从最旧的消息开始丢弃(直到达标),被裁段生成摘要插回开头
 * 3. 摘要生成优先 LLM,失败回退提取式摘要
 *
 * 摘要消息带 [ContextBuilder.SUMMARY_MARK] 前缀,便于 UI 提示与后续识别。
 */
@Singleton
class ContextBuilder @Inject constructor() {

    /**
     * 构建请求上下文。
     * @param memoryBlocks 核心记忆块(Letta core memory),渲染进 system prompt
     * @param session 当前会话消息(按序)
     * @param budget token 预算
     * @param summarize 摘要压缩执行器;null 表示禁用 LLM 压缩(只用提取式回退)
     * @param workingMemory 按当前问题检索到的记忆(工作记忆),注入 system prompt
     * @param userId 记忆库用户标识(告知模型 memory_recall 该传哪个 user_id)
     */
    suspend fun build(
        memoryBlocks: List<MemoryBlock>,
        session: List<ChatMsg>,
        budget: ContextBudget,
        summarize: (suspend (List<ContextTurn>, String?) -> String)? = null,
        workingMemory: String = "",
        userId: String = MemoryStore.LOCAL_USER_ID,
        cachedSummary: CachedSummary? = null,
        /** 本轮是否真的附带工具定义:为 false 时不注入「工具使用约定」,免得教它用没有的东西。 */
        toolsEnabled: Boolean = true,
    ): Built {
        var systemTokens = 0
        var truncatedToolResults = 0
        var keptCount = 0
        var compactedCount = 0
        var summaryTokens = 0
        val parts = mutableListOf<ChatMessage>()

        // 1. system prompt = 记忆块(限额压缩后)+ 基础人设
        val memoryPrompt = MemoryCodec.toPrompt(MemoryCodec.enforceLimits(memoryBlocks))
        val systemText = buildString {
            append(memoryPrompt)
            if (workingMemory.isNotBlank()) {
                append("\n\n## Working memory (retrieved for this question; ignore irrelevant entries)\n")
                append(workingMemory)
            }
            append("\n\nYou are the Freebuff assistant. Use the provided tools for current information (web search, GitHub, calculations, time). Respond in the user's language; default to English. ")
            // 早停(「几句话就停」)的两道提示防线:多步任务要推进到完成;完成必须有显式信号
            append("Keep casual chat and single questions concise. For multi-step tasks, keep working until the goal is reached. Use tools for the next step each round. ")
            append("Do not only describe plans or stop midway to report progress. ")
            append("When the task is complete, call ").append(AgentLoop.COMPLETION_TOOL)
                .append(" (summary must describe work done and remaining items). Ask questions only when the user needs to decide. ")
            append("Process tool results into a useful answer. ")
            append("\nMemory tools: memory_recall searches historical memories (preferences, facts, progress); save_memory updates core blocks (persona=your identity, user=about the user, project=task focus). ")
            append("Current user_id=").append(userId).append('。')
            if (toolsEnabled) {
                // 工具协议与「错误信封」的读法:每轮都注入,弱模型才不会把报错当成答案。
                // 依 Anthropic《Writing effective tools for agents》:错误响应要能指引下一步;
                // 这里把「怎么读错、该重试几次、什么时候该换路」写进系统提示。
                append("\n\n## Tool usage rules\n")
                append("- Use only documented argument names in a JSON object. Ask the user for missing information rather than inventing it.\n")
                append("- If a result starts with “[Tool error]”, follow its Problem / How to fix guidance and retry once. After two consecutive failures, switch tools or tell the user what is blocking progress.\n")
                append("- Use only tool names from the provided list. If uncertain, start with web_search; do not invent tool names.")
            }
        }
        parts += ChatMessage.text("system", systemText)
        systemTokens = ContextPolicy.estimateTokens(systemText)

        // 2. 序列化:工具结果先做机械压缩(单条超限才动)
        val turns = session
            .filter { (it.role == "user" || it.role == "agent") && (it.text.isNotBlank() || it.tools.isNotEmpty()) }
            .map { m ->
                ContextTurn(
                    role = m.role,
                    text = m.text,
                    toolOutputs = m.tools.map { c ->
                        val compressed = ContextPolicy.compressToolResult(c.output)
                        if (compressed != c.output) truncatedToolResults++
                        c.tool to (c.state to compressed)
                    },
                )
            }
        val historyTurns = turns.size

        // 3. 预算裁剪:从最旧开始丢弃,永远保留最后 KEEP_RECENT 条
        var kept = turns
        while (kept.size > KEEP_RECENT && totalTokens(parts, kept) > budget.usableTokens) {
            kept = kept.drop(1)
        }
        val droppedTurns = turns.take(turns.size - kept.size)
        keptCount = kept.size
        compactedCount = droppedTurns.size

        // 4. 被裁段生成摘要:优先复用已有摘要(见 CachedSummary),否则 LLM,失败回退提取式
        var summaryText: String? = null
        if (droppedTurns.isNotEmpty()) {
            val reusable = cachedSummary?.takeIf { it.coversTurns >= droppedTurns.size - SUMMARY_GROWTH_STEP }
            val summary = when {
                reusable != null -> reusable.text
                summarize != null -> try {
                    // 把已有摘要一并交给模型:它是「早前那段」的压缩,新压的只需合并进去
                    summarize(droppedTurns, cachedSummary?.text)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    extractiveFallback(droppedTurns)
                }
                else -> extractiveFallback(droppedTurns)
            }
            if (summary.isNotBlank()) {
                summaryText = summary
                // 摘要写进 system 提示尾部,而不是插一条新的 system 消息:
                // OpenAI 兼容网关惯例把 system 当首条,中途再插一条行为不一(实测提醒消息踩过同款)
                val withSummary = systemText + "\n\n" + SUMMARY_MARK +
                    "(Earlier conversation condensed into the following summary)\n" + summary
                parts[0] = ChatMessage.text("system", withSummary)
                systemTokens = ContextPolicy.estimateTokens(withSummary)
                summaryTokens = ContextPolicy.estimateTokens(summary)
            }
        }

        // 5. 近端消息:工具结果以附注形式并入文本(OpenAI 兼容;结构化 tool 回传只用于循环当轮)
        kept.forEach { t ->
            val text = if (t.toolOutputs.isEmpty()) t.text
            else buildString {
                append(t.text)
                t.toolOutputs.forEach { (tool, pair) ->
                    val (state, output) = pair
                    append("\n\n[").append(tool).append(" → ").append(if (state == "error") "Failed" else "Done").append("]\n")
                    append(output)
                }
            }
            parts += ChatMessage.text(if (t.role == "agent") "assistant" else "user", text)
        }

        // 6. 最终仍超预算:从最旧的近端消息继续丢弃(永不丢弃 system)
        while (parts.size > 1 + KEEP_RECENT && parts.sumOf { ContextPolicy.estimateTokens(it.content) } > budget.usableTokens) {
            if (parts.size <= 2) break
            parts.removeAt(1)
        }

        val total = parts.sumOf { ContextPolicy.estimateTokens(it.content) }
        return Built(
            messages = parts,
            summary = if (droppedTurns.isNotEmpty()) summaryText else null,
            summaryCoverage = droppedTurns.size,
            stats = ContextStats(
                systemTokens = systemTokens,
                historyTurns = historyTurns,
                keptTurns = keptCount,
                compactedTurns = compactedCount,
                truncatedToolResults = truncatedToolResults,
                summaryTokens = summaryTokens,
                totalTokens = total,
            ),
        )
    }

    /** 提取式摘要回退:无法调用 LLM 时的降级方案。 */
    private fun extractiveFallback(dropped: List<ContextTurn>): String {
        val firstUser = dropped.firstOrNull { it.role == "user" }?.text.orEmpty()
        val lastAgent = dropped.lastOrNull { it.role == "agent" }?.text.orEmpty()
        return ContextPolicy.extractiveSummary(firstUser, lastAgent, dropped.size)
    }

    private fun totalTokens(parts: List<ChatMessage>, kept: List<ContextTurn>): Int =
        parts.sumOf { ContextPolicy.estimateTokens(it.content) } +
            kept.sumOf { t ->
                ContextPolicy.estimateTokens(t.text) +
                    t.toolOutputs.sumOf { ContextPolicy.estimateTokens(it.second.second) }
            }

    companion object {
        /** 摘要标记(system 提示里的段落头;UI 提示「已压缩早期上下文」另有统计驱动)。 */
        const val SUMMARY_MARK = "[Earlier conversation summary]"

        /** 预算裁剪时永远保留的最近消息条数。 */
        const val KEEP_RECENT = 8

        /**
         * 复用已有摘要的滞后量:被裁段比上次多出的条数不超过这个值时直接复用。
         *
         * 为什么需要:长会话里每一轮/每一轮次都会重新裁剪,若每次都调一次 LLM 摘要,
         * 一次对话能白烧十几次摘要请求(实测:每轮都调,单次几十秒)。
         * 留一点滞后,换来「每个 turn 顶多一次摘要」。
         */
        const val SUMMARY_GROWTH_STEP = 4
    }
}

/** 已有摘要(可复用):coversTurns 表示它覆盖了多少条被裁消息。 */
data class CachedSummary(val coversTurns: Int, val text: String)

/** 一次构建的产物:请求消息 + 统计(+ 本次用到的摘要,供调用方缓存)。 */
data class Built(
    val messages: List<ChatMessage>,
    val stats: ContextStats,
    val summary: String? = null,
    val summaryCoverage: Int = 0,
)
