package com.freebuff.core.model

/**
 * 清洗自定义模型:丢弃脏条目(null/id 为空)、补齐字段类型(名称为空填"未命名模型")、
 * 过滤空白模型快照。返回清洗后列表与修复报告(与原型行为一致)。
 */
fun sanitizeCustomModels(list: List<CustomModel?>): Pair<List<CustomModel>, RepairReport> {
    val items = mutableListOf<RepairItem>()
    var fixed = 0
    val out = mutableListOf<CustomModel>()
    list.forEachIndexed { i, c ->
        if (c == null || c.id.isBlank()) {
            items.add(RepairItem(i + 1, listOf(RepairFix("drop", 1)), null, null))
            fixed++
            return@forEachIndexed
        }
        val fixes = mutableListOf<RepairFix>()
        var name = c.name
        if (name.isBlank()) {
            name = "未命名模型"
            fixes.add(RepairFix("name", 1))
        }
        var apiId = c.apiId
        if (apiId.isBlank()) {
            apiId = ""
            fixes.add(RepairFix("field", 1))
        }
        var base = c.base
        if (base.isBlank()) {
            base = ""
            fixes.add(RepairFix("field", 1))
        }
        val models = c.models.filter { it.isNotBlank() }
        if (models.size != c.models.size) fixes.add(RepairFix("models", c.models.size - models.size))
        val rec = c.copy(name = name, apiId = apiId, base = base, models = models)
        if (fixes.isNotEmpty()) {
            fixed++
            items.add(RepairItem(i + 1, fixes, c, rec))
        }
        out.add(rec)
    }
    val rep = RepairReport(fixed, items, list.size, out.size, list)
    return out to rep
}
