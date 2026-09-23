package com.freebuff.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.model.FEEDBACK_URL
import com.freebuff.core.model.LATEST_VERSION
import com.freebuff.core.model.OFFICIAL_SITE
import com.freebuff.core.ui.RowCard
import com.freebuff.core.ui.SectionLabel
import com.freebuff.core.ui.SegRow
import com.freebuff.core.ui.SetRow
import com.freebuff.core.ui.openExternal
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.LocalAppNavigator
import com.freebuff.core.ui.theme.LocalTokens

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
                    icon = "⚡",
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
                    icon = "🗑",
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
                    sub = "概念原型的原生实现 · 说明与后续计划",
                    onClick = { navigator.openSheet(AppNavState.SHEET_ABOUT) },
                )
                SetRow(
                    icon = "↻",
                    title = "版本",
                    sub = "点击检查最新版本",
                    trailing = "v" + LATEST_VERSION,
                    onClick = { navigator.openSheet(AppNavState.SHEET_UPDATE) },
                )
                SetRow(
                    icon = "↗",
                    title = "官方网站",
                    sub = OFFICIAL_SITE,
                    onClick = { openExternal(context, OFFICIAL_SITE) },
                )
                SetRow(
                    icon = "💬",
                    title = "反馈与建议",
                    sub = "在官方开源仓库提交 issue",
                    onClick = { openExternal(context, FEEDBACK_URL) },
                )
            }

            /* ---------------- 页脚 ---------------- */
            Spacer(Modifier.height(22.dp))
            Column(Modifier.align(Alignment.CenterHorizontally)) {
                Text("Freebuff Mobile", color = t.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("v" + LATEST_VERSION, color = t.text3, fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 3.dp))
                Text("Made for the Freebuff open-source project", color = t.text3, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
