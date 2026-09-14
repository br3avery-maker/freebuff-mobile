package com.freebuff.core.model

/* ---------------- 官方模型目录:内置 + 网关实时 ---------------- */

/**
 * 把官方网关 /v1/models 返回的模型 id 列表整理成目录条目。
 * - 已知 id:复用内置目录的展示元数据(名称/徽标/等级/描述)
 * - 未知 id:自动生成展示信息,保证任何网关模型都能列出
 * - 去重并保持网关返回顺序
 */
fun officialModelsFromIds(ids: List<String>): List<OfficialModel> {
    val seen = LinkedHashSet<String>()
    return ids.mapNotNull { raw ->
        val id = raw.trim()
        if (id.isEmpty() || !seen.add(id)) return@mapNotNull null
        OFFICIAL_MODELS.firstOrNull { it.id == id } ?: synthesizedOfficial(id)
    }
}

/** 内置(离线)官方目录:网关未配置或拉取失败时的回退。 */
val builtinOfficialModels: List<OfficialModel> get() = OFFICIAL_MODELS

/** 未知网关模型的展示信息:名称美化 + 徽标缩写。 */
fun synthesizedOfficial(id: String): OfficialModel = OfficialModel(
    id = id,
    name = prettifyModelId(id),
    badge = badgeOf(id),
    tier = "full",
    tierText = "完整访问",
    desc = "来自官方网关的实时目录",
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
