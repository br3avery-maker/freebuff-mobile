package com.freebuff.core.model

/**
 * 清洗自定义模型:丢弃脏条目、补齐字段类型、过滤空白模型快照。返回清洗后列表与修复报告(与原型一致)。
 *
 * 行为约定:
 * - null 条目(解析阶段的非对象/JSON null)记为 drop:报告项 raw=null,恢复时无法落库,界面标注「无法恢复」
 * - ID 为空的条目**不再丢弃**:自动生成「cm-」前缀 ID(fix code "id"),记录保留、差异可见、可恢复
 * - 名称为空补「未命名模型」;apiId/base 空白记为 field 修复;快照过滤非文本项记为 models 修复
 */
fun sanitizeCustomModels(list: List<CustomModel?>): Pair<List<CustomModel>, RepairReport> {
    val items = mutableListOf<RepairItem>()
    var fixed = 0
    val out = mutableListOf<CustomModel>()
    list.forEachIndexed { i, c ->
        if (c == null) {
            items.add(RepairItem(i + 1, listOf(RepairFix("drop", 1)), null, null))
            fixed++
            return@forEachIndexed
        }
        val fixes = mutableListOf<RepairFix>()
        var id = c.id
        if (id.isBlank()) {
            id = "cm-" + uid()
            fixes.add(RepairFix("id", 1))
        }
        var name = c.name
        if (name.isBlank()) {
            name = "Unnamed model"
            fixes.add(RepairFix("name", 1))
        }
        var fieldFixes = 0
        var apiId = c.apiId
        if (apiId.isBlank()) {
            apiId = ""
            fieldFixes++
        }
        var base = c.base
        if (base.isBlank()) {
            base = ""
            fieldFixes++
        }
        if (fieldFixes > 0) fixes.add(RepairFix("field", fieldFixes))
        val models = c.models.filter { it.isNotBlank() }
        if (models.size != c.models.size) fixes.add(RepairFix("models", c.models.size - models.size))
        val rec = c.copy(id = id, name = name, apiId = apiId, base = base, models = models)
        if (fixes.isNotEmpty()) {
            fixed++
            items.add(RepairItem(i + 1, fixes, c, rec))
        }
        out.add(rec)
    }
    val rep = RepairReport(fixed, items, list.size, out.size, list)
    return out to rep
}
