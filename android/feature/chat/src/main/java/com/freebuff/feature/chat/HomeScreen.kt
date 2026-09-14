package com.freebuff.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.model.Session
import com.freebuff.core.ui.RFull
import com.freebuff.core.ui.SectionLabel
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.LocalAppNavigator
import com.freebuff.core.ui.theme.LocalTokens

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    val navigator = LocalAppNavigator.current
    val sessions by viewModel.sessions.collectAsState()
    val query by viewModel.query.collectAsState()
    val modelName by viewModel.modelName.collectAsState()
    Column(Modifier.fillMaxSize().padding(top = 54.dp)) {
        Text("会话", color = t.text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(10.dp))
        SearchRow(query, viewModel::updateQuery)
        ModelChip(modelName = modelName, onClick = viewModel::openModelSheet)
        if (viewModel.isDemoBuild) {
            DemoStrip("演示构建 · 未配置官方网关,对话与仓库使用内置数据。")
        }
        Spacer(Modifier.height(6.dp))
        if (sessions.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(120.dp))
                if (query.isNotBlank()) {
                    Text("没有匹配的会话", color = t.text2, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("换个关键字试试,或清空搜索框", color = t.text3, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp))
                } else {
                    Text("还没有会话", color = t.text2, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("点右下角「+」发起你的第一个任务", color = t.text3, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
                item { SectionLabel("最近", Modifier.padding(start = 20.dp, top = 16.dp)) }
                items(sessions, key = { it.id }) { s ->
                    SessionRow(
                        s = s,
                        onOpen = { viewModel.openChat(s.id) },
                        onMenu = { navigator.openSheet(AppNavState.SHEET_SESSION_MENU + ":" + s.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelChip(modelName: String, onClick: () -> Unit) {
    val t = LocalTokens.current
    Row(
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 10.dp)
            .clip(RFull)
            .background(t.chip)
            .clickable { onClick() }
            .padding(start = 9.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(RFull).background(t.accent))
        Spacer(Modifier.width(6.dp))
        Text(
            modelName.ifBlank { "选择模型" },
            color = t.text2, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 170.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text("⌄", color = t.text3, fontSize = 12.sp)
    }
}

/** 原型中的说明条(演示构建时提示数据来源)。 */
@Composable
private fun DemoStrip(text: String) {
    val t = LocalTokens.current
    Row(
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.accentSoft)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text("ⓘ", color = t.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(text, color = t.text2, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun SearchRow(query: String, onQuery: (String) -> Unit) {
    val t = LocalTokens.current
    Row(
        modifier = Modifier.padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.surface)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🔍", color = t.text3, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("搜索会话", color = t.text3, fontSize = 13.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = t.text, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Text("✕", color = t.text3, fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onQuery("") }
                    .padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun SessionRow(s: Session, onOpen: () -> Unit, onMenu: () -> Unit) {
    val t = LocalTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .background(t.surface)
            .padding(horizontal = 18.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.title, color = t.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Text(s.time, color = t.text3, fontSize = 11.sp,
                modifier = Modifier.padding(start = 8.dp))
            Text("⋯", color = t.text3, fontSize = 16.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onMenu() }
                    .padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Text(s.preview.ifEmpty { s.messages.lastOrNull()?.text ?: "" },
            color = t.text2, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp))
    }
}
