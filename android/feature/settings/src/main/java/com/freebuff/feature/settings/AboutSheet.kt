package com.freebuff.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.freebuff.core.ui.RFull
import com.freebuff.core.ui.SheetScaffold
import com.freebuff.core.ui.navigation.LocalAppNavigator
import com.freebuff.core.ui.theme.LocalTokens

/**
 * 关于 Freebuff Mobile:能力说明 + 后续计划。
 * 文案按原生工程的实际接入状态描述(对话走真实网络、密钥加密落库),
 * 不再沿用 HTML 原型「所有回复均为预置模拟」的措辞。
 */
@Composable
fun AboutSheet(viewModel: SettingsViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    val navigator = LocalAppNavigator.current
    // 版本显示用实际安装的包版本(BuildConfig.VERSION_NAME),不再落库、不可被写脏
    val version = viewModel.appVersion
    SheetScaffold("About Freebuff Mobile", "Freebuff Mobile · v" + version) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(t.accentSoft),
                    contentAlignment = Alignment.Center,
                ) { Text(">_", color = t.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Bring your ideas to Freebuff", color = t.text2, fontSize = 13.sp)
                    Text("Freebuff Mobile · v" + version, color = t.text, fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Text(ABOUT_BODY, color = t.text2, fontSize = 13.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(14.dp))
            Text("Future plans", color = t.text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(ABOUT_PLAN, color = t.text2, fontSize = 13.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(20.dp))
            Text(
                "Got it",
                color = t.accentInk, fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RFull).background(t.accent)
                    .clickable { navigator.closeSheet() }.padding(vertical = 12.dp),
            )
        }
    }
}

private const val ABOUT_BODY =
    "Freebuff Mobile English fork: talk to an assistant and turn ideas into code and tasks.\n" +
        "Chat uses real network requests: built-in models use the Freebuff gateway; custom models connect directly to your OpenAI-compatible endpoint. " +
        "Git accounts use GitHub OAuth device authorization to load your repositories.\n" +
        "Chats, settings, and custom models stay in the local database. API keys and Git tokens are encrypted using Android Keystore before storage."

private const val ABOUT_PLAN =
    "1) Expand in-app updates (download, verification, installation);\n" +
        "2) Support more Git providers, commits, and pull requests;\n" +
        "3) Subagent orchestration for parallel research and code changes."
