package com.freebuff.core.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.pow

/**
 * 检索式记忆库(区别于 [MemoryBlock] 常驻块)。
 *
 * 设计:记忆同时是一个「工具」——
 * 1. 每轮对话结束后由模型提取值得长期保留的信息(偏好/事实/进度)入库,按类型结构化
 * 2. 每次 LLM 调用前按当前查询检索 Top K 相关条目,作为「工作记忆」注入提示词
 * 3. 模型亦可主动调用 memory_recall(user_id/query/top_k/memory_type) 拉取历史记忆
 *
 * 纯函数实现(分词/打分/解析),便于单测;持久化见 core/data 的 MemoryEntryRepository。
 */

/** 记忆类型:长期(偏好、关键事实)/ 短期(任务进度、临时状态)。 */
object MemoryType {
    const val LONG_TERM = "long_term"
    const val SHORT_TERM = "short_term"

    val ALL = listOf(LONG_TERM, SHORT_TERM)

    /** 归一化模型给的 type:兼容 long/short、中文「长期/短期」;非法返回 null。 */
    fun normalize(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        LONG_TERM, "long", "长期" -> LONG_TERM
        SHORT_TERM, "short", "短期" -> SHORT_TERM
        else -> null
    }

    /** 注入提示词用的中文标签。 */
    fun label(type: String): String = if (type == SHORT_TERM) "短期" else "长期"
}

/** 记忆库常量。 */
object MemoryStore {
    /** 本机(访客模式)用户标识:隔离多用户记忆的默认命名空间。 */
    const val LOCAL_USER_ID = "local"

    /** memory_recall 默认检索条数。 */
    const val DEFAULT_TOP_K = 5

    /** memory_recall 允许的最大条数(防止模型一次拉爆上下文)。 */
    const val MAX_TOP_K = 20

    /** 单用户保留的最大条目数,超出后淘汰最旧(记忆库无限膨胀会拖慢检索并挤占预算)。 */
    const val PRUNE_KEEP = 500

    /** 初始条目最大落地条数(防止记忆被少数超长条目挤占);条目按 token 均摊预算。 */
    const val MAX_ENTRY_CHARS = 600
}

/** 记忆库中的一条记忆。 */
data class MemoryEntry(
    val id: Long = 0,
    val userId: String = MemoryStore.LOCAL_USER_ID,
    val type: String = MemoryType.LONG_TERM,
    val content: String,
    /** 来源会话 id(便于回溯「哪次对话记下的」)。 */
    val sessionId: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** 被检索命中次数(热度:越常被用到越靠前)。 */
    val hits: Int = 0,
)

/** memory_recall 工具的参数与结果格式。 */
object MemoryRecallCodec {

    /** 工具参数:user_id(必填)/ query / top_k(默认 5)/ memory_type(可选)。 */
    data class Args(
        val userId: String,
        val query: String,
        val topK: Int,
        val memoryType: String?,
    )

    fun parse(argsJson: String): Args {
        val o = try { JSONObject(argsJson) } catch (e: Exception) { JSONObject() }
        val rawK = o.optInt("top_k", MemoryStore.DEFAULT_TOP_K)
        return Args(
            userId = o.optString("user_id").trim(),
            query = o.optString("query").trim(),
            topK = rawK.coerceIn(1, MemoryStore.MAX_TOP_K),
            memoryType = MemoryType.normalize(o.optString("memory_type").takeIf { it.isNotBlank() && it != "null" }),
        )
    }

    /** 工具结果文本:回传给模型的编号列表(带类型标签与来源时间)。 */
    fun formatResult(userId: String, query: String, entries: List<MemoryEntry>): String {
        if (entries.isEmpty()) return "记忆库中没有与「${query.take(60)}」相关的条目(user_id=$userId)。"
        val body = entries.mapIndexed { i, e -> "${i + 1}. [${MemoryType.label(e.type)}] ${e.content.trim()}" }
        return "检索到 ${entries.size} 条相关记忆(user_id=$userId, query=${query.take(60)}):\n" + body.joinToString("\n")
    }

    /** 提示词注入格式:「N. [类型] 内容」。 */
    fun formatForPrompt(entries: List<MemoryEntry>): String =
        entries.mapIndexed { i, e -> "${i + 1}. [${MemoryType.label(e.type)}] ${e.content.trim()}" }.joinToString("\n")
}

/**
 * 词法相关度检索(无嵌入依赖,端侧可离线运行):
 * - 分词:CJK 连续串切 bigram(中文无空格,bigram 是免分词的经典近似)+ 整串特征;拉丁/数字按词小写
 * - 打分:TF 对数加权 / 长度归一 + 查询命中率缩放 + 子串命中加成 + 时间衰减 + 命中热度
 * - 空查询(纯符号)退化为按时间/热度排序
 *
 * 复杂度 O(候选条目数 × 查询词数),记忆库规模(百级)下无压力。
 */
object MemoryRetrieval {

    private const val RECENCY_WEIGHT = 1.2
    private const val SUBSTRING_BONUS = 2.0
    private const val HITS_WEIGHT = 0.25
    private const val LONG_TERM_HALF_LIFE_DAYS = 30.0
    private const val SHORT_TERM_HALF_LIFE_DAYS = 2.0

    /** 是否为 CJK 字符(含中日韩统一表意文字与假名)。 */
    private fun isCjk(ch: Char): Boolean = ch.code >= 0x2E80

    /** 分词结果用于「查询 ↔ 条目」的特征匹配。 */
    fun tokenize(text: String): List<String> {
        val out = mutableListOf<String>()
        val cjk = StringBuilder()
        val latin = StringBuilder()

        fun flushCjk() {
            if (cjk.isEmpty()) return
            val s = cjk.toString()
            if (s.length == 1) out += s
            else {
                for (i in 0 until s.length - 1) out += s.substring(i, i + 2)
                if (s.length > 2) out += s // 整串也作为特征(专有名词、代号);长度 2 时与 bigram 重复
            }
            cjk.clear()
        }

        fun flushLatin() {
            if (latin.isEmpty()) return
            val t = latin.toString().lowercase()
            if (t.length >= 2) out += t
            latin.clear()
        }

        for (ch in text) {
            when {
                isCjk(ch) -> { flushLatin(); cjk.append(ch) }
                ch.isLetterOrDigit() -> { flushCjk(); latin.append(ch) }
                else -> { flushCjk(); flushLatin() }
            }
        }
        flushCjk(); flushLatin()
        return out
    }

    /** 时间衰减:长期记忆半衰期 30 天,短期 2 天(任务进度很快过时)。 */
    private fun recencyBoost(entry: MemoryEntry, now: Long): Double {
        val ts = if (entry.updatedAt > 0) entry.updatedAt else entry.createdAt
        if (ts <= 0 || now <= ts) return 0.0
        val days = (now - ts) / 86_400_000.0
        val halfLife = if (entry.type == MemoryType.SHORT_TERM) SHORT_TERM_HALF_LIFE_DAYS else LONG_TERM_HALF_LIFE_DAYS
        return RECENCY_WEIGHT * 0.5.pow(days / halfLife)
    }

    /** 命中热度(被检索过越多越靠前)。 */
    private fun hitsBoost(entry: MemoryEntry): Double =
        if (entry.hits > 0) HITS_WEIGHT * ln(1.0 + entry.hits) else 0.0

    /**
     * 词法相关度(只看查询词与条目内容的特征重叠;0 = 完全无关)。
     * 与排序辅助项(时间/热度)分离,便于「有查询词时只返回真正相关条目」。
     */
    fun relevance(query: String, entry: MemoryEntry): Double {
        val q = tokenize(query)
        val content = entry.content
        val freq = tokenize(content).groupingBy { it }.eachCount()
        var s = 0.0
        if (q.isNotEmpty() && freq.isNotEmpty()) {
            val lenNorm = 1.0 + ln(1.0 + freq.values.sum())
            val distinct = q.distinct()
            var matched = 0
            for (t in distinct) {
                val tf = freq[t] ?: 0
                if (tf > 0) {
                    matched++
                    s += (1.0 + ln(tf.toDouble())) / lenNorm
                }
            }
            // 命中率缩放:查询词覆盖越全越可信,避免单字偶然命中拿到高分
            if (matched > 0) s *= 1.0 + 0.5 * (matched.toDouble() / distinct.size)
            val trimmed = query.trim()
            if (trimmed.length >= 2 && content.contains(trimmed)) s += SUBSTRING_BONUS
        }
        return s
    }

    /** 综合得分 = 词法相关度 + 排序辅助项(时间衰减 + 命中热度)。 */
    fun score(query: String, entry: MemoryEntry, now: Long): Double =
        relevance(query, entry) + recencyBoost(entry, now) + hitsBoost(entry)

    /**
     * 排序取 Top K。
     * 查询无有效特征(纯符号/空白)时按新鲜度排序;得分全为 0 时同样退化,保证总能给出「最近记忆」。
     */
    fun rank(query: String, candidates: List<MemoryEntry>, topK: Int, now: Long): List<MemoryEntry> {
        if (candidates.isEmpty()) return emptyList()
        val k = topK.coerceIn(1, MemoryStore.MAX_TOP_K)
        val scored = candidates.map { it to score(query, it, now) }
        // 有查询词时只返回真正有特征重叠的条目(无关条目不得靠时间/热度混进来);
        // 查询无有效特征(纯符号/空白)时按新鲜度返回「最近记忆」。
        val pool = if (tokenize(query).isNotEmpty()) {
            scored.filter { relevance(query, it.first) > 0.0 }
        } else scored
        return pool
            .sortedWith(
                compareByDescending<Pair<MemoryEntry, Double>> { it.second }
                    .thenByDescending { if (it.first.updatedAt > 0) it.first.updatedAt else it.first.createdAt },
            )
            .take(k)
            .map { it.first }
    }
}

/** 轮次结束后从对话中提取的记忆项。 */
object MemoryExtraction {

    data class Item(val type: String, val content: String)

    /** 单轮最多入库条数(提取器偶尔会输出长列表,截断避免噪声). */
    const val MAX_ITEMS = 5

    /** 单条内容最大长度,超出截断。 */
    const val MAX_CONTENT_CHARS = 300

    /**
     * 元信息/工具闲聊类内容不是可复用记忆——如「无记录」「记忆库为空」「已调用检索工具」。
     * 提取器偶尔把助手的空转叙述当事实记下(实测发生过),这里做一道确定性护栏。
     */
    private val META_NOISE = Regex(
        "(无记录|没有任何记录|未找到相关|记忆库为空|记忆库中|调用.{0,8}(工具|检索)|为空[。.,,])",
    )

    /**
     * 解析提取器输出的 JSON。
     * 容错:剥掉 ```json 围栏 / 前后夹带说明,只取第一个 [ ... ] 数组;
     * 元素可为 {"type","content"} 对象或纯字符串(默认长期记忆)。解析失败返回空表(静默跳过)。
     */
    fun parse(raw: String): List<Item> {
        val text = raw.trim().removeCodeFence()
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val arr = try { JSONArray(text.substring(start, end + 1)) } catch (e: Exception) { return emptyList() }
        val out = mutableListOf<Item>()
        val seen = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            if (out.size >= MAX_ITEMS) break
            val content: String
            val type: String
            val obj = arr.optJSONObject(i)
            if (obj != null) {
                content = obj.optString("content").trim()
                type = MemoryType.normalize(obj.optString("type")) ?: MemoryType.LONG_TERM
            } else {
                content = arr.optString(i).trim()
                type = MemoryType.LONG_TERM
            }
            if (content.length < 2) continue
            if (META_NOISE.containsMatchIn(content)) continue // 丢弃「无记录」类空转叙述
            val normalized = content.replace(Regex("\\s+"), " ").take(MAX_CONTENT_CHARS)
            if (!seen.add(normalized)) continue
            out += Item(type, normalized)
        }
        return out
    }

    private fun String.removeCodeFence(): String =
        replace(Regex("(?s)```(?:json)?\\s*"), "").replace("```", "")
}
