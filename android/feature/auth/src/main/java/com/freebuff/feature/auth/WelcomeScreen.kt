package com.freebuff.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.ui.theme.LocalTokens

@Composable
fun WelcomeScreen(viewModel: WelcomeViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(90.dp))
        Box(
            modifier = Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(t.surface)
                .clickable { viewModel.cycleTheme() },
            contentAlignment = Alignment.Center,
        ) {
            Text(">_", color = t.accent, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text("把想法,交给", color = t.text, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Freebuff", color = t.accent, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("官方安卓 App · 一个随身携带的编码 Agent:会规划、会改代码、会解释,并且完全免费。",
            color = t.text2, fontSize = 13.5.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        FeatureRow("多个前沿模型,无需订阅或 API Key")
        FeatureRow("Agent 自动规划、执行并解释每一步")
        FeatureRow("会话本地保存 · 随时离线可用")
        Spacer(Modifier.weight(1f))
        PrimaryBtn("登录 Freebuff 账号") { viewModel.enter(signedIn = true) }
        Spacer(Modifier.height(10.dp))
        Text("先逛逛(访客模式)", color = t.text3, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { viewModel.enter(signedIn = false) }.padding(12.dp))
        Spacer(Modifier.height(8.dp))
        Text("访客模式 · 数据仅存于本机", color = t.text3, fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun FeatureRow(text: String) {
    val t = LocalTokens.current
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("✦", color = t.accent, fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        Text(text, color = t.text2, fontSize = 13.sp)
    }
}

@Composable
private fun PrimaryBtn(text: String, onClick: () -> Unit) {
    val t = LocalTokens.current
    Text(text, color = t.accentInk, fontSize = 15.5.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.accent)
            .clickable { onClick() }.padding(vertical = 15.dp),
        textAlign = TextAlign.Center)
}
