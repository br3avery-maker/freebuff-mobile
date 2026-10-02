package com.freebuff.core.model

/* ---------------- 官方模型目录:只来自官方网关 ---------------- */

/**
 * 把官方网关 `/v1/models` 返回的模型 id 列表整理成目录条目。
 * - 去重并保持网关返回顺序
 * - 展示信息(名称/徽标/等级/描述)由 id 自动生成,保证任何网关模型都能列出
 * - 空 id 丢弃
 *
 * 不再有「内置官方目录」:官方模型一律由网关实时提供,未配置网关时该列表为空,
 * 模型面板只列自定义模型并明确提示未配置。
 */
fun officialModelsFromIds(ids: List<String>): List<OfficialModel> {
    val seen = LinkedHashSet<String>()
    return ids.mapNotNull { raw ->
        val id = raw.trim()
        if (id.isEmpty() || !seen.add(id)) return@mapNotNull null
        synthesizedOfficial(id)
    }
}

/** 网关模型的展示信息:名称美化 + 徽标缩写。 */
fun synthesizedOfficial(id: String): OfficialModel = OfficialModel(
    id = id,
    name = prettifyModelId(id),
    badge = badgeOf(id),
    tier = "full",
    tierText = "Full access",
    desc = "Live catalog from the built-in gateway",
)

private val MODEL_ACRONYMS = setOf("gpt", "glm", "llm", "api", "ai", "ds", "vl", "moe", "sdk", "phi")

/**
 * 模型 id 美化:"deepseek-v4-flash" → "Deepseek V4 Flash"。
 * 常见缩写保持大写,数字段原样保留。
 */
fun prettifyModelId(id: String): String =
    id.split('-', '_', '/', ':', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { tok ->
            when {
                tok.lowercase() in MODEL_ACRONYMS -> tok.uppercase()
                tok.first().isDigit() -> tok
                else -> tok.replaceFirstChar { c -> c.uppercase() }
            }
        }
        .ifBlank { id }

/** 徽标:取 id 的字母数字前缀,最长 3 位。 */
fun badgeOf(id: String): String =
    id.filter { it.isLetterOrDigit() }.take(3).uppercase().ifBlank { "AI" }
