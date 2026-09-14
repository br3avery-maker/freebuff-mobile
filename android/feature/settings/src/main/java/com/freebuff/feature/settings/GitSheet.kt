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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.data.repository.GitConnectStep
import com.freebuff.core.ui.R14
import com.freebuff.core.ui.RFull
import com.freebuff.core.ui.SheetScaffold
import com.freebuff.core.ui.openExternal
import com.freebuff.core.ui.navigation.LocalAppNavigator
import com.freebuff.core.ui.theme.LocalTokens

private data class Provider(val key: String, val name: String, val host: String, val real: Boolean)

private val PROVIDERS = listOf(
    Provider("github", "GitHub", "github.com", true),
    Provider("gitlab", "GitLab", "gitlab.com", false),
    Provider("gitee", "Gitee", "gitee.com", false),
)

/**
 * Git 账号:关联 / 断开。
 * - 配置了 client_id:走真实 GitHub 设备流,展示用户码并轮询直到授权完成
 * - 未配置:演示账号,点击即完成
 */
@Composable
fun GitSheet(viewModel: SettingsViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val g by viewModel.git.collectAsState()
    val step by viewModel.gitConnect.collectAsState()

    // 拿到用户码后自动拉起浏览器授权页(仅一次)
    val awaiting = step as? GitConnectStep.AwaitingUser
    LaunchedEffect(awaiting?.userCode) {
        awaiting?.let { openExternal(context, it.verificationUri) }
    }

    SheetScaffold("Git 账号", "关联后可在发起任务时直接读取你的仓库") {
        when {
            g.connected -> ConnectedCard(
                provider = g.provider,
                login = g.login,
                name = g.name,
                onRevoke = {
                    viewModel.revokeGit()
                    navigator.openSheet(null)
                },
            )

            step is GitConnectStep.AwaitingUser -> AwaitingCard(
                userCode = (step as GitConnectStep.AwaitingUser).userCode,
                verificationUri = (step as GitConnectStep.AwaitingUser).verificationUri,
                onOpen = { uri -> openExternal(context, uri) },
                onCopy = { code -> clipboard.setText(AnnotatedString(code)) },
                onCancel = { viewModel.cancelGitConnect() },
            )

            else -> ProviderList(
                demo = viewModel.isGitDemo,
                onPick = { viewModel.connectGit() },
            )
        }
    }
}

@Composable
private fun ConnectedCard(provider: String, login: String, name: String, onRevoke: () -> Unit) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().clip(R14).background(t.surface2).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(provider.uppercase().take(1), color = t.accentInk, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(t.accent).padding(horizontal = 9.dp, vertical = 5.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(name.ifEmpty { login }, color = t.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(login + " · 已连接", color = t.text3, fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("断开连接", color = t.danger, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RFull).clickable { onRevoke() }.padding(horizontal = 18.dp, vertical = 9.dp))
    }
}

/** 设备流等待页:展示用户码 + 打开授权页 + 轮询状态,可取消。 */
@Composable
private fun AwaitingCard(
    userCode: String,
    verificationUri: String,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().clip(R14).background(t.surface2).padding(16.dp)) {
        Text("在浏览器完成授权", color = t.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("已打开 GitHub 授权页,输入下面的用户码并确认:", color = t.text3, fontSize = 11.5.sp,
            modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(userCode, color = t.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(t.surface).padding(horizontal = 14.dp, vertical = 8.dp))
            Spacer(Modifier.width(10.dp))
            Text("复制", color = t.text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RFull).clickable { onCopy(userCode) }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(verificationUri, color = t.text3, fontSize = 11.sp)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("重新打开授权页", color = t.accentInk, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RFull).background(t.accent).clickable { onOpen(verificationUri) }
                    .padding(horizontal = 16.dp, vertical = 9.dp))
            Spacer(Modifier.width(10.dp))
            Text("取消", color = t.text2, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RFull).clickable { onCancel() }.padding(horizontal = 14.dp, vertical = 9.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("等待授权中…保持本页打开,完成后会自动关联", color = t.text3, fontSize = 11.sp)
    }
}

@Composable
private fun ProviderList(
    demo: Boolean,
    onPick: (Provider) -> Unit,
) {
    val t = LocalTokens.current
    val shown = if (demo) PROVIDERS else PROVIDERS.filter { it.real }
    shown.forEach { p ->
        val dot = when (p.key) {
            "github" -> t.accent
            "gitlab" -> t.tier
            else -> t.warn
        }
        Row(Modifier.fillMaxWidth().clip(R14).background(t.surface2).clickable { onPick(p) }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(RFull).background(dot))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, color = t.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(p.host, color = t.text3, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    if (demo) {
        Text("演示环境:未配置 GITHUB_OAUTH_CLIENT_ID,点击即完成模拟 OAuth 授权",
            color = t.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
    } else {
        Text("将通过 GitHub 设备流授权(浏览器输入用户码),只读取账号与仓库列表",
            color = t.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
    }
}
