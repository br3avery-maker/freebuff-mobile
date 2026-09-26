package com.freebuff.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.model.FEEDBACK_URL
import com.freebuff.core.model.OFFICIAL_SITE
import com.freebuff.core.model.ToolPermission
import com.freebuff.core.ui.RowCard
import com.freebuff.core.ui.SectionLabel
import com.freebuff.core.ui.SegRow
import com.freebuff.core.ui.SetRow
import com.freebuff.core.ui.openExternal
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.LocalAppNavigator
import com.freebuff.core.ui.theme.LocalTokens

/**
 * 工具权限配置区(工具调用开启时展示):逐工具三级分级(免确认/需确认/禁止)。
 * 行内展开;显示「已自定义」标记,改动即时生效并持久化到 settings 表。
 */
@Composable
private fun ToolPermissionSection(viewModel: SettingsViewModel) {
    val t = LocalTokens.current
    val overrides by viewModel.toolPermissionOverrides.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("工具权限", color = t.text2, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
        Text(if (overrides.isEmpty()) "默认" else overrides.size.toString() + " 项已自定义 · 展开调整 ⌄",
            color = t.text3, fontSize = 11.sp,
            modifier = Modifier.clickable { expanded = !expanded })
    }
    if (expanded) {
        Spacer(Modifier.height(8.dp))
        com.freebuff.core.model.DefaultTools.ALL.forEach { tool ->
            val effective = overrides[tool.name] ?: com.freebuff.core.model.ToolPermissions.defaultFor(tool.name)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        com.freebuff.core.model.toolDisplayName(tool.name),
                        color = t.text, fontSize = 12.sp,
                    )
                    Text(tool.name, color = t.text3, fontSize = 9.5.sp)
                }
                SegRow(
                    listOf("免确认", "需确认", "禁止"),
                    when (effective) {
                        ToolPermission.ALLOW -> 0
                        ToolPermission.CONFIRM -> 1
                        ToolPermission.DENY -> 2
                    },
                    // 有界宽度:不传会让 SegRow 吃掉整行宽度,把左侧工具名挤成 0 宽(不可见)
                    Modifier.width(180.dp),
                ) { i ->
                    viewModel.setToolPermission(
                        tool.name,
                        when (i) { 0 -> ToolPermission.ALLOW; 2 -> ToolPermission.DENY; else -> ToolPermission.CONFIRM },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("免确认:静默执行;需确认:每次执行前弹窗;禁止:不执行并告知模型。改动即时生效。",
            color = t.text3, fontSize = 10.sp)
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    val themeMode by viewModel.themeMode.collectAsState()
    val modelName by viewModel.modelName.collectAsState()
    val git by viewModel.git.collectAsState()
    val repoParse by viewModel.repoParse.collectAsState()
    val toolsEnabled by viewModel.toolsEnabled.collectAsState()
    val memoryEnabled by viewModel.memoryEnabled.collectAsState()
    val reasoningMode by viewModel.reasoningMode.collectAsState()
    val customModels by viewModel.customModels.collectAsState()
    val sessionCount by viewModel.sessionCount.collectAsState()
    val clearArmed by viewModel.clearArmed.collectAsState()

    Column(Modifier.fillMaxSize().padding(top = 54.dp)) {
        Text("设置", color = t.text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 130.dp)) {

            /* ---------------- 外观 ---------------- */
            SectionLabel("外观", Modifier.padding(top = 16.dp, bottom = 8.dp))
            RowCard {
                val themes = listOf("跟随系统", "浅色", "深色")
                val idx = when (themeMode) { "light" -> 1; "dark" -> 2; else -> 0 }
                Text("主题", color = t.text2, fontSize = 13.5.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
                SegRow(themes, idx) { i -> viewModel.setTheme(listOf("system", "light", "dark")[i]) }
            }

            /* ---------------- 模型 ---------------- */
            SectionLabel("模型", Modifier.padding(top = 18.dp, bottom = 8.dp))
            RowCard {
                SetRow(
                    // 图标一律用等宽字形符号:emoji(⚡/🗑/💬)在浅色主题下只按自己的彩色渲染,实测对比度仅 1.5–2.6:1
                    icon = "✦",
                    title = modelName.ifBlank { "选择模型" },
                    sub = "点击选择对话使用的模型",
                    onClick = { viewModel.openModelSheet() },
                )
                Spacer(Modifier.height(14.dp))
                Text("工具调用", color = t.text2, fontSize = 13.5.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
                SegRow(listOf("开启", "关闭"), if (toolsEnabled) 0 else 1) { i ->
                    viewModel.setToolsEnabled(i == 0)
                }
                if (toolsEnabled) {
                    Spacer(Modifier.height(14.dp))
                    ToolPermissionSection(viewModel)
                }
                Spacer(Modifier.height(14.dp))
                Text("上下文记忆", color = t.text2, fontSize = 13.5.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
                SegRow(listOf("开启", "关闭"), if (memoryEnabled) 0 else 1) { i ->
                    viewModel.setMemoryEnabled(i == 0)
                }
                Spacer(Modifier.height(14.dp))
                Text("深度思考", color = t.text2, fontSize = 13.5.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
                SegRow(
                    listOf("关闭", "自动", "开启"),
                    when (reasoningMode) {
                        com.freebuff.core.model.Reasoning.MODE_OFF -> 0
                        com.freebuff.core.model.Reasoning.MODE_ON -> 2
                        else -> 1
                    },
                ) { i ->
                    viewModel.setReasoningMode(
                        listOf(
                            com.freebuff.core.model.Reasoning.MODE_OFF,
                            com.freebuff.core.model.Reasoning.MODE_AUTO,
                            com.freebuff.core.model.Reasoning.MODE_ON,
                        )[i],
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "自动:按模型识别思考参数(DeepSeek 推理 / Qwen3 / GPT-5 / GLM 等);" +
                        "开启:未识别的模型也试 enable_thinking。思考内容折叠在回复上方的「思考过程」里。",
                    color = t.text3, fontSize = 10.sp,
                )
            }

            /* ---------------- 集成 ---------------- */
            SectionLabel("集成", Modifier.padding(top = 18.dp, bottom = 8.dp))
            RowCard {
                SetRow(
                    icon = "◈",
                    title = "Git 账号",
                    sub = "授权后可在发起任务时直接读取你的仓库",
                    trailing = if (git.connected) git.name.ifEmpty { git.login } + " · 已连接" else "未连接",
                    accent = git.connected,
                    onClick = { navigator.openSheet(AppNavState.SHEET_GIT) },
                )
                Spacer(Modifier.height(14.dp))
                Text("仓库地址解析", color = t.text2, fontSize = 13.5.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
                SegRow(listOf("严格解析", "宽松原样"),
                    if (repoParse == "strict") 0 else 1) { i ->
                    viewModel.setRepoParse(if (i == 0) "strict" else "loose")
                }
                Spacer(Modifier.height(14.dp))
                SetRow(
                    icon = "▣",
                    title = "自定义模型",
                    sub = "接入 OpenAI 兼容 API 端点,可作为对话模型使用",
                    trailing = if (customModels.isEmpty()) "未添加" else customModels.size.toString() + " 个",
                    accent = customModels.isNotEmpty(),
                    onClick = { navigator.openSheet(AppNavState.SHEET_CUSTOM_MODELS) },
                )
            }

            /* ---------------- 会话与数据 ---------------- */
            SectionLabel("会话与数据", Modifier.padding(top = 18.dp, bottom = 8.dp))
            RowCard {
                SetRow(
                    icon = "▤",
                    title = "数据保存在本机",
                    sub = "会话、设置与自定义模型仅存于当前设备,不上传",
                )
                Spacer(Modifier.height(4.dp))
                SetRow(
                    icon = "✕",
                    title = if (clearArmed) "再次点击确认清除" else "清除全部会话",
                    sub = if (clearArmed) "2 秒内再次点击生效,超时自动取消"
                    else "删除本机全部会话(" + sessionCount + " 个),不可恢复",
                    danger = true,
                    onClick = {
                        if (clearArmed) viewModel.clearAllSessions() else viewModel.armClear()
                    },
                )
            }

            /* ---------------- 关于 ---------------- */
            SectionLabel("关于", Modifier.padding(top = 18.dp, bottom = 8.dp))
            RowCard {
                SetRow(
                    icon = "ⓘ",
                    title = "关于 Freebuff Mobile",
                    sub = "能力说明、数据去向与后续计划",
                    onClick = { navigator.openSheet(AppNavState.SHEET_ABOUT) },
                )
                SetRow(
                    icon = "↻",
                    title = "版本",
                    sub = "点击检查最新版本",
                    trailing = "v" + viewModel.appVersion,
                    onClick = { navigator.openSheet(AppNavState.SHEET_UPDATE) },
                )
                SetRow(
                    icon = "↗",
                    title = "官方网站",
                    sub = OFFICIAL_SITE,
                    onClick = { openExternal(context, OFFICIAL_SITE) },
                )
                SetRow(
                    icon = "✎",
                    title = "反馈与建议",
                    sub = "在官方开源仓库提交 issue",
                    onClick = { openExternal(context, FEEDBACK_URL) },
                )
            }

            /* ---------------- 页脚 ---------------- */
            Spacer(Modifier.height(22.dp))
            Column(Modifier.align(Alignment.CenterHorizontally)) {
                Text("Freebuff Mobile", color = t.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("v" + viewModel.appVersion, color = t.text3, fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 3.dp))
                Text("Made for the Freebuff open-source project", color = t.text3, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
