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
        Text("Tool permissions", color = t.text2, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
        Text(if (overrides.isEmpty()) "Default" else overrides.size.toString() + " customized · Expand to adjust ⌄",
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
                    listOf("Auto-allow", "Ask first", "Blocked"),
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
        Text("Auto-allow runs immediately. Ask first shows a confirmation. Blocked prevents execution and tells the model. Changes apply immediately.",
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
        Text("Settings", color = t.text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 130.dp)) {

            /* ---------------- 外观 ---------------- */
            SectionLabel("Appearance", Modifier.padding(top = 16.dp, bottom = 8.dp))
            RowCard {
                val themes = listOf("System", "Light", "Dark")
                val idx = when (themeMode) { "light" -> 1; "dark" -> 2; else -> 0 }
                Text("Theme", color = t.text2, fontSize = 13.5.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
                SegRow(themes, idx) { i -> viewModel.setTheme(listOf("system", "light", "dark")[i]) }
            }

            /* ---------------- 模型 ---------------- */
            SectionLabel("Model", Modifier.padding(top = 18.dp, bottom = 8.dp))
            RowCard {
                SetRow(
                    // 图标一律用等宽字形符号:emoji(⚡/🗑/💬)在浅色主题下只按自己的彩色渲染,实测对比度仅 1.5–2.6:1
                    icon = "✦",
                    title = modelName.ifBlank { "Choose a model" },
                    sub = "Tap to choose the model for chats",
                    onClick = { viewModel.openModelSheet() },
                )
                Spacer(Modifier.height(14.dp))
                Text("Tool calling", color = t.text2, fontSize = 13.5.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
                SegRow(listOf("On", "Off"), if (toolsEnabled) 0 else 1) { i ->
                    viewModel.setToolsEnabled(i == 0)
                }
                if (toolsEnabled) {
                    Spacer(Modifier.height(14.dp))
                    ToolPermissionSection(viewModel)
                }
                Spacer(Modifier.height(14.dp))
                Text("Context memory", color = t.text2, fontSize = 13.5.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
                SegRow(listOf("On", "Off"), if (memoryEnabled) 0 else 1) { i ->
                    viewModel.setMemoryEnabled(i == 0)
                }
                Spacer(Modifier.height(14.dp))
                Text("Reasoning", color = t.text2, fontSize = 13.5.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
                SegRow(
                    listOf("Off", "Auto", "On"),
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
                    "Auto detects reasoning parameters for supported models (DeepSeek, Qwen3, GPT-5, GLM, etc.). " +
                        "On also tries enable_thinking for unrecognized models. Reasoning appears in a collapsible section above replies.",
                    color = t.text3, fontSize = 10.sp,
                )
            }

            /* ---------------- 集成 ---------------- */
            SectionLabel("Integrations", Modifier.padding(top = 18.dp, bottom = 8.dp))
            RowCard {
                SetRow(
                    icon = "◈",
                    title = "Git account",
                    sub = "Connect to load your repositories when starting a task",
                    trailing = if (git.connected) git.name.ifEmpty { git.login } + " · Connected" else "Not connected",
                    accent = git.connected,
                    onClick = { navigator.openSheet(AppNavState.SHEET_GIT) },
                )
                Spacer(Modifier.height(14.dp))
                Text("Repository URL parsing", color = t.text2, fontSize = 13.5.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
                SegRow(listOf("Strict", "As entered"),
                    if (repoParse == "strict") 0 else 1) { i ->
                    viewModel.setRepoParse(if (i == 0) "strict" else "loose")
                }
                Spacer(Modifier.height(14.dp))
                SetRow(
                    icon = "▣",
                    title = "Custom models",
                    sub = "Add an OpenAI-compatible API endpoint to use as a chat model",
                    trailing = if (customModels.isEmpty()) "Not added" else customModels.size.toString() + "",
                    accent = customModels.isNotEmpty(),
                    onClick = { navigator.openSheet(AppNavState.SHEET_CUSTOM_MODELS) },
                )
            }

            /* ---------------- 会话与数据 ---------------- */
            SectionLabel("Chats and data", Modifier.padding(top = 18.dp, bottom = 8.dp))
            RowCard {
                SetRow(
                    icon = "▤",
                    title = "Data stored on this device",
                    sub = "Chats, settings, and custom models are stored locally and are not uploaded",
                )
                Spacer(Modifier.height(4.dp))
                SetRow(
                    icon = "✕",
                    title = if (clearArmed) "Tap again to confirm" else "Clear all chats",
                    sub = if (clearArmed) "Tap again within 2 seconds; otherwise this cancels automatically"
                    else "Delete all local chats (" + sessionCount + "). This cannot be undone.",
                    danger = true,
                    onClick = {
                        if (clearArmed) viewModel.clearAllSessions() else viewModel.armClear()
                    },
                )
            }

            /* ---------------- 关于 ---------------- */
            SectionLabel("About", Modifier.padding(top = 18.dp, bottom = 8.dp))
            RowCard {
                SetRow(
                    icon = "ⓘ",
                    title = "About Freebuff Mobile",
                    sub = "Features, data handling, and future plans",
                    onClick = { navigator.openSheet(AppNavState.SHEET_ABOUT) },
                )
                SetRow(
                    icon = "↻",
                    title = "Version",
                    sub = "Tap to check for the latest version",
                    trailing = "v" + viewModel.appVersion,
                    onClick = { navigator.openSheet(AppNavState.SHEET_UPDATE) },
                )
                SetRow(
                    icon = "↗",
                    title = "Website",
                    sub = OFFICIAL_SITE,
                    onClick = { openExternal(context, OFFICIAL_SITE) },
                )
                SetRow(
                    icon = "✎",
                    title = "Feedback",
                    sub = "Open an issue in this fork's repository",
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
