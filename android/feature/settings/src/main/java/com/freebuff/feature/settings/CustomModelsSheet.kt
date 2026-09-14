package com.freebuff.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.model.CustomModel
import com.freebuff.core.model.Probe
import com.freebuff.core.model.endpointUrl
import com.freebuff.core.model.fmtT
import com.freebuff.core.ui.R14
import com.freebuff.core.ui.RFull
import com.freebuff.core.ui.SheetScaffold
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.LocalAppNavigator
import com.freebuff.core.ui.theme.LocalTokens

/** 自定义模型列表:CRUD 入口、模型快照、修复报告与恢复。 */
@Composable
fun CustomModelsSheet(viewModel: SettingsViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    val navigator = LocalAppNavigator.current
    val customModels by viewModel.customModels.collectAsState()
    val repairReport by viewModel.repairReport.collectAsState()
    val repairRestored by viewModel.repairRestored.collectAsState()
    var expanded by remember { mutableStateOf<String?>(null) }
    SheetScaffold("自定义模型", "管理你自己的 API 端点") {
        Text("添加自定义模型", color = t.accentInk, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(R14).background(t.accent).clickable {
                navigator.openSheet(AppNavState.SHEET_CUSTOM_FORM)
            }.padding(horizontal = 16.dp, vertical = 9.dp))
        Spacer(Modifier.height(14.dp))
        val rr = repairReport
        if (rr != null && !repairRestored) {
            RepairBanner(viewModel, rr)
            Spacer(Modifier.height(12.dp))
        }
        if (customModels.isEmpty()) {
            Text("还没有自定义模型,点上方按钮添加第一个", color = t.text3, fontSize = 12.5.sp,
                modifier = Modifier.padding(top = 26.dp))
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
                items(customModels, key = { it.id }) { c ->
                    CustomRow(
                        vm = viewModel,
                        c = c,
                        isExpanded = expanded == c.id,
                        onToggle = { id -> expanded = if (expanded == id) null else id },
                        onEdit = { navigator.openSheet(AppNavState.SHEET_CUSTOM_FORM + ":" + c.id) },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun CustomRow(
    vm: SettingsViewModel,
    c: CustomModel,
    isExpanded: Boolean,
    onToggle: (String) -> Unit,
    onEdit: () -> Unit,
) {
    val t = LocalTokens.current
    val modelId by vm.modelId.collectAsState()
    Column(Modifier.fillMaxWidth().clip(R14).background(t.surface).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("M", color = t.accentInk, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(t.warn).padding(horizontal = 7.dp, vertical = 3.dp))
            Spacer(Modifier.width(8.dp))
            Text(c.name, color = t.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            if (c.id == modelId) Text("当前", color = t.accent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
        }
        Text(c.apiId, color = t.text2, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
        Text(endpointUrl(c.base), color = t.text3, fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text("使用", color = t.accentInk, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RFull).background(t.accent).clickable {
                    vm.setModelId(c.id)
                }.padding(horizontal = 11.dp, vertical = 5.dp))
            Spacer(Modifier.width(8.dp))
            Text("编辑", color = t.text2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RFull).background(t.surface2).clickable { onEdit() }
                    .padding(horizontal = 12.dp, vertical = 5.dp))
            Spacer(Modifier.weight(1f))
            if (c.models.isNotEmpty()) {
                Text((if (isExpanded) "▾" else "▸") + " 上次可用 " + c.models.size + " 个模型",
                    color = t.accent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RFull).background(t.accentSoft).clickable { onToggle(c.id) }
                        .padding(horizontal = 9.dp, vertical = 5.dp))
            }
        }
        if (isExpanded && c.models.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            SnapshotPanel(vm, c)
        }
    }
}

@Composable
private fun SnapshotPanel(vm: SettingsViewModel, c: CustomModel) {
    val t = LocalTokens.current
    var bulk by remember { mutableStateOf(false) }
    Text("快照模型 ID", color = t.text3, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
    c.models.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            row.forEach { mid -> SnapshotCell(vm, c, mid, Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(if (bulk) "◐ 正在重新测试连接…" else "重新测试连接",
        color = if (bulk) t.accent else t.text2, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RFull).background(t.surface2).clickable {
            if (!bulk) {
                bulk = true
                vm.probeModel(c) { (updated, result) ->
                    if (result is com.freebuff.core.data.network.ProbeResult.Success) {
                        vm.updateCustomModel(updated)
                    }
                    bulk = false
                }
            }
        }.padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
private fun SnapshotCell(vm: SettingsViewModel, c: CustomModel, mid: String, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val p = c.probe[mid]
    var testing by remember { mutableStateOf(false) }
    val dot = if (testing) t.warn else if (p != null) t.ok else t.surface3
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(t.surface2).clickable {
        vm.updateCustomModel(c.copy(apiId = mid))
    }.padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RFull).background(dot).clickable {
                if (p == null && !testing) {
                    testing = true
                    vm.probeModel(c) { (updated, result) ->
                        if (result is com.freebuff.core.data.network.ProbeResult.Success) {
                            vm.updateCustomModel(updated)
                        }
                        testing = false
                    }
                }
            })
            Spacer(Modifier.width(6.dp))
            Text(mid, color = t.text, fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (mid == c.apiId) Text("当前", color = t.accent, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
        }
        p?.let { pr ->
            Text("上次连通 " + fmtT(pr.at) + " · " + pr.ms + "ms",
                color = t.text3, fontSize = 9.5.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
    Spacer(Modifier.width(6.dp))
}

@Composable
private fun RepairBanner(vm: SettingsViewModel, rep: com.freebuff.core.model.RepairReport) {
    val t = LocalTokens.current
    var showReport by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(R14).background(t.warn.copy(alpha = 0.12f)).padding(12.dp)) {
        if (!confirming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚠ 已自动修复 " + rep.fixed + " 条异常记录", color = t.warn, fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (showReport) "收起" else "查看报告", color = t.warn, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RFull).clickable { showReport = !showReport }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            if (showReport) {
                Text("原始 " + rep.rawN + " 条 → 保留 " + rep.nowN + " 条(修复 " + rep.fixed + " 条)",
                    color = t.text2, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                rep.items.forEach { it ->
                    val line = it.fixes.joinToString(" ") { f ->
                        when (f.code) {
                            "drop" -> "丢弃非对象记录"
                            "name" -> "补全名称为「未命名模型」"
                            "field" -> "清理空字段"
                            "models" -> "过滤 " + f.n + " 项非文本快照"
                            else -> f.code
                        }
                    }
                    Text("· 第 " + it.i + " 条:" + line, color = t.text3, fontSize = 10.5.sp,
                        modifier = Modifier.padding(top = 4.dp, start = 2.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("恢复原始记录", color = t.warn, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RFull).background(t.warn.copy(alpha = 0.18f)).clickable {
                    confirming = true
                }.padding(horizontal = 13.dp, vertical = 6.dp))
        } else {
            Text("确认恢复原始记录?", color = t.warn, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("以下差异确认后才会恢复(仅本次会话,不写入存档)", color = t.text3, fontSize = 11.sp,
                modifier = Modifier.padding(top = 3.dp))
            rep.items.forEach { it ->
                if (it.fixes.any { f -> f.code == "drop" }) {
                    Text("· 第 " + it.i + " 条:该记录已丢弃 → 恢复后将重新出现", color = t.text2, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 7.dp))
                } else {
                    val raw = it.raw as? CustomModel
                    val fx = it.fixed as? CustomModel
                    val diffs = mutableListOf<String>()
                    it.fixes.forEach { f ->
                        when (f.code) {
                            "name" -> diffs.add("名称「" + (raw?.name ?: "") + "」→「" + (fx?.name ?: "") + "」")
                            "field" -> diffs.add("Base URL / 模型 ID 空字段已清理")
                            "models" -> diffs.add("快照过滤 " + f.n + " 项非文本 → 剩 " + (fx?.models?.size ?: 0) + " 项")
                        }
                    }
                    Text("· 第 " + it.i + " 条 原始 → 已修复:", color = t.text2, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 7.dp))
                    diffs.forEach { d ->
                        Text("    " + d, color = t.text3, fontSize = 10.5.sp,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("取消", color = t.text3, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RFull).clickable { confirming = false }.padding(horizontal = 18.dp, vertical = 8.dp))
                Spacer(Modifier.width(10.dp))
                Text("确认恢复", color = t.accentInk, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RFull).background(t.warn).clickable { vm.restoreRaw() }.padding(horizontal = 20.dp, vertical = 8.dp))
            }
        }
    }
}
