package com.freebuff.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.model.RemoteVersion
import com.freebuff.core.ui.RFull
import com.freebuff.core.ui.SheetScaffold
import com.freebuff.core.ui.theme.LocalTokens

/**
 * 检查更新:真实拉取远程版本 JSON,有更新则展示更新说明,同意后提升版本号。
 * 未配置更新源、网络失败、解析失败分别给出可读提示并支持重试。
 */
@Composable
fun UpdateSheet(viewModel: SettingsViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    val version by viewModel.version.collectAsState()
    var phase by remember { mutableStateOf("checking") }
    var remote by remember { mutableStateOf<RemoteVersion?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        phase = "checking"
        error = null
        viewModel.checkForUpdate { rv, hasUpdate, err ->
            remote = rv
            error = err
            phase = when {
                err != null -> "error"
                hasUpdate -> "update"
                else -> "latest"
            }
        }
    }

    SheetScaffold("检查更新", "当前版本 v" + version) {
        when (phase) {
            "checking" -> Column {
                Text("正在检查更新…", color = t.text2, fontSize = 14.sp)
                Text("从远程更新源拉取版本信息", color = t.text3, fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 5.dp))
            }

            "error" -> Column {
                Text("检查更新失败", color = t.danger, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(error ?: "未知错误", color = t.text2, fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(20.dp))
                Text("重试", color = t.accentInk, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RFull).background(t.accent).clickable { attempt++ }
                        .padding(horizontal = 26.dp, vertical = 11.dp))
                Text(if (viewModel.updateConfigured) "请确认网络与更新源地址可达"
                else "未配置更新源:在 app 模块 buildConfigField 设置 UPDATE_URL",
                    color = t.text3, fontSize = 10.5.sp, modifier = Modifier.padding(top = 8.dp))
            }

            "update" -> {
                val rv = remote
                Column {
                    Text("有新版本可用", color = t.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("最新版本 v" + (rv?.version ?: "") + " 已发布,包含新功能与修复", color = t.text2, fontSize = 12.5.sp,
                        modifier = Modifier.padding(top = 6.dp))
                    if (rv?.notes.isNullOrEmpty().not()) {
                        Spacer(Modifier.height(14.dp))
                        Text("更新内容", color = t.text3, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                        rv!!.notes.forEach { i ->
                            Text("• " + i, color = t.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("同意并更新", color = t.accentInk, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RFull).background(t.accent).clickable {
                            viewModel.applyUpdate(rv?.version ?: "")
                            phase = "done"
                        }.padding(horizontal = 26.dp, vertical = 11.dp))
                    Text("下载与安装为后续接入点(本次仅升级版本号)",
                        color = t.text3, fontSize = 10.5.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }

            "done" -> Column {
                Text("更新完成 ✓", color = t.ok, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("已更新至 v" + (remote?.version ?: ""), color = t.text2, fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }

            else -> Column {
                Text("已是最新版本 ✓", color = t.ok, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("当前 v" + version + " 已是最新",
                    color = t.text2, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
