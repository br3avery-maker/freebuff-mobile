package com.freebuff.feature.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.ui.RowCard
import com.freebuff.core.ui.SectionLabel
import com.freebuff.core.ui.SegRow
import com.freebuff.core.ui.SetRow
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.LocalAppNavigator
import com.freebuff.core.ui.theme.LocalTokens

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    val navigator = LocalAppNavigator.current
    val themeMode by viewModel.themeMode.collectAsState()
    val git by viewModel.git.collectAsState()
    val repoParse by viewModel.repoParse.collectAsState()
    val customModels by viewModel.customModels.collectAsState()
    val version by viewModel.version.collectAsState()
    Column(Modifier.fillMaxSize().padding(top = 54.dp)) {
        Text("设置", color = t.text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(6.dp))
        rememberScrollState().let { sc ->
            Column(Modifier.fillMaxSize().verticalScroll(sc).padding(start = 16.dp, end = 16.dp, bottom = 130.dp)) {
                SectionLabel("外观", Modifier.padding(top = 16.dp, bottom = 8.dp))
                RowCard {
                    val themes = listOf("跟随系统", "浅色", "深色")
                    val idx = when (themeMode) { "light" -> 1; "dark" -> 2; else -> 0 }
                    Text("主题", color = t.text2, fontSize = 13.5.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
                    SegRow(themes, idx) { i -> viewModel.setTheme(listOf("system", "light", "dark")[i]) }
                }
                SectionLabel("集成", Modifier.padding(top = 18.dp, bottom = 8.dp))
                RowCard {
                    SetRow(
                        icon = "◈",
                        title = "Git 账号",
                        trailing = if (git.connected) git.name.ifEmpty { git.login } + " · 已连接" else "未关联",
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
                        trailing = if (customModels.isEmpty()) "未添加" else customModels.size.toString() + " 个",
                        accent = customModels.isNotEmpty(),
                        onClick = { navigator.openSheet(AppNavState.SHEET_CUSTOM_MODELS) },
                    )
                }
                SectionLabel("通用", Modifier.padding(top = 18.dp, bottom = 8.dp))
                RowCard {
                    SetRow(
                        icon = "↻",
                        title = "检查最新版本",
                        trailing = "当前 v" + version,
                        onClick = { navigator.openSheet(AppNavState.SHEET_UPDATE) },
                    )
                }
                SectionLabel("关于", Modifier.padding(top = 18.dp, bottom = 8.dp))
                RowCard {
                    Text("Freebuff Desktop", color = t.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("官方安卓 App · 概念演示", color = t.text2, fontSize = 12.5.sp,
                        modifier = Modifier.padding(top = 4.dp))
                    Text("由 Freebuff 官方开发 · 免费不限量", color = t.text3, fontSize = 11.5.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("v" + version + " · 仅演示,数据保存在本机", color = t.text3, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}
