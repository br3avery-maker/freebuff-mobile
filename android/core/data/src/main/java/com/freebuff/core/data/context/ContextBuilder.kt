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
                append("\n\n## 工作记忆(按当前问题从记忆库检索;与当前问题无关则忽略)\n")
                append(workingMemory)
            }
            append("\n\n你是 Freebuff 助手。可使用提供的工具获取实时信息(联网搜索/GitHub/计算/时间)。")
            // 早停(「几句话就停」)的两道提示防线:多步任务要推进到完成;完成必须有显式信号
            append("闲聊与单轮问答保持简洁;多步任务要持续推进到目标达成为止 —— 每轮用工具推进下一步,")
            append("不要只描述计划、也不要中途停下汇报进度。")
            append("任务真正完成时调用 ").append(AgentLoop.COMPLETION_TOOL)
                .append("(summary 写清做了什么与遗留事项);需要用户决定时才停下来提问。")
            append("工具结果仅供你参考加工。")
            append("\n记忆工具:memory_recall 按需检索历史记忆(用户偏好/关键事实/任务进度),save_memory 更新核心记忆块(persona=你的身份,user=关于用户,project=任务焦点)。")
            append("当前用户 user_id=").append(userId).append('。')
            if (toolsEnabled) {
                // 工具协议与「错误信封」的读法:每轮都注入,弱模型才不会把报错当成答案。
                // 依 Anthropic《Writing effective tools for agents》:错误响应要能指引下一步;
                // 这里把「怎么读错、该重试几次、什么时候该换路」写进系统提示。
                append("\n\n## 工具使用约定\n")
                append("- 参数只写说明书里声明的名字,放进 JSON 对象;缺哪个参数就先问用户,不要自己编。\n")
                append("- 结果以「[工具错误]」开头时,照「问题 / 怎么改」两行修正后再试一次;同一工具连续出错两次就换工具或直接告诉用户卡在哪里。\n")
                append("- 工具名只能用工具清单里的;不确定用哪个就先 web_search,不要自己造工具名。")
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
                    "(被裁掉的早期对话已压缩为摘要,以下是摘要正文)\n" + summary
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
                    append("\n\n[").append(tool).append(" → ").append(if (state == "error") "失败" else "完成").append("]\n")
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
        const val SUMMARY_MARK = "[早期对话摘要]"

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
