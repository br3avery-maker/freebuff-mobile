package com.freebuff.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.model.RemoteVersion
import com.freebuff.core.ui.RFull
import com.freebuff.core.ui.SheetScaffold
import com.freebuff.core.ui.theme.LocalTokens

/**
 * 检查更新:真实拉取更新源 JSON(默认本仓库公开的 dist/update.json,发版时自动刷新)。
 * 有新版本时给出更新说明与发布页下载入口 —— App 不自行安装 APK,下载安装后版本号随包更新。
 * 未配置更新源、网络失败、解析失败分别给出可读提示并支持重试。
 */
@Composable
fun UpdateSheet(viewModel: SettingsViewModel = hiltViewModel()) {
    val uriHandler = LocalUriHandler.current
    val t = LocalTokens.current
    /** 版本显示用实际安装的包版本,避免「标记为已更新但装的是旧包」的错位。 */
    val version = viewModel.appVersion
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
                Text(
                    if (viewModel.updateConfigured) "从更新源 " + viewModel.updateSource + " 拉取版本信息"
                    else "更新源未配置",
                    color = t.text3, fontSize = 11.5.sp, modifier = Modifier.padding(top = 5.dp),
                )
            }

            "error" -> Column {
                Text("检查更新失败", color = t.danger, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(error ?: "未知错误", color = t.text2, fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(20.dp))
                Text("重试", color = t.accentInk, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RFull).background(t.accent).clickable { attempt++ }
                        .padding(horizontal = 26.dp, vertical = 11.dp))
                Text(
                    if (viewModel.updateConfigured)
                        "请确认网络可达、更新源地址正确(当前 " + viewModel.updateSource + ")"
                    else "未配置更新源:设 freebuff.updateUrl(android/local.properties)或 FREEBUFF_UPDATE_URL",
                    color = t.text3, fontSize = 10.5.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }

            "update" -> {
                val rv = remote
                val downloadUrl = rv?.url.orEmpty()
                Column {
                    Text("有新版本可用", color = t.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("最新版本 v" + (rv?.version ?: "") + ",你当前是 v" + version,
                        color = t.text2, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
                    if (!rv?.notes.isNullOrEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text("更新内容", color = t.text3, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                        rv!!.notes.forEach { i ->
                            Text("• " + i, color = t.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    if (downloadUrl.isNotBlank()) {
                        Text("前往下载", color = t.accentInk, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RFull).background(t.accent).clickable {
                                uriHandler.openUri(downloadUrl)
                            }.padding(horizontal = 26.dp, vertical = 11.dp))
                        Text("打开发布页下载并安装新 APK,安装后版本号随包更新",
                            color = t.text3, fontSize = 10.5.sp, modifier = Modifier.padding(top = 8.dp))
                    } else {
                        Text("更新源未提供下载地址:请到发布页手动下载安装",
                            color = t.text3, fontSize = 10.5.sp)
                    }
                }
            }

            else -> Column {
                Text("已是最新版本 ✓", color = t.ok, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("当前 v" + version + " 已是最新", color = t.text2, fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 6.dp))
                Text("更新源 " + viewModel.updateSource, color = t.text3, fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
