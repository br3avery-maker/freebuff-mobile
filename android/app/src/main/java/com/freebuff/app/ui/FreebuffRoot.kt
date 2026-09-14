package com.freebuff.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.app.RootViewModel
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.AppNavigator
import com.freebuff.core.ui.navigation.LocalAppNavigator
import com.freebuff.core.ui.theme.DarkTokens
import com.freebuff.core.ui.theme.FreebuffTheme
import com.freebuff.core.ui.theme.LightTokens
import com.freebuff.core.ui.theme.LocalTokens
import com.freebuff.feature.auth.WelcomeScreen
import com.freebuff.feature.chat.ChatScreen
import com.freebuff.feature.chat.ChatViewModel
import com.freebuff.feature.chat.HomeScreen
import com.freebuff.feature.chat.ModelSheet
import com.freebuff.feature.chat.SessionMenuSheet
import com.freebuff.feature.chat.TaskWizardSheet
import com.freebuff.feature.settings.CustomModelFormSheet
import com.freebuff.feature.settings.CustomModelsSheet
import com.freebuff.feature.settings.GitSheet
import com.freebuff.feature.settings.SettingsScreen
import com.freebuff.feature.settings.SettingsViewModel
import com.freebuff.feature.settings.UpdateSheet

/**
 * 应用根:路由 when + BottomBar + ModalSheetHost + snackbar。
 * 所有 ViewModel 绑定 Activity 级 ViewModelStore,此处 hiltViewModel()
 * 与 feature screen 拿到的是同一实例——任务向导提交后直接驱动 ChatViewModel。
 */
@Composable
fun FreebuffRoot(navigator: AppNavigator) {
    val rootVm: RootViewModel = hiltViewModel()
    val chatVm: ChatViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val navState by navigator.state.collectAsState()
    val themeMode by settingsVm.themeMode.collectAsState()
    val repairReport by rootVm.repairReport.collectAsState()

    // 一次性迁移:把清洗修复报告注入设置页,供「恢复原始记录」
    LaunchedEffect(repairReport) {
        repairReport?.let {
            settingsVm.setRepairReport(it)
            rootVm.consumeRepairReport()
        }
    }

    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val tokens = if (dark) DarkTokens else LightTokens

    CompositionLocalProvider(LocalAppNavigator provides navigator) {
        FreebuffTheme(darkTheme = dark) {
            CompositionLocalProvider(LocalTokens provides tokens) {
                val snackHost = remember { SnackbarHostState() }
                val snackMessage = navState.snackMessage
                LaunchedEffect(snackMessage) {
                    snackMessage?.let {
                        snackHost.showSnackbar(it)
                        navigator.consumeSnack()
                    }
                }
                Box(Modifier.fillMaxSize().background(tokens.bg)) {
                    when (navState.route) {
                        AppNavState.ROUTE_WELCOME -> WelcomeScreen()
                        AppNavState.ROUTE_HOME -> HomeScreen()
                        AppNavState.ROUTE_CHAT -> ChatScreen()
                        AppNavState.ROUTE_SETTINGS -> SettingsScreen()
                    }
                    if (navState.route == AppNavState.ROUTE_HOME || navState.route == AppNavState.ROUTE_SETTINGS) {
                        BottomBar(navigator, Modifier.align(Alignment.BottomCenter))
                    }
                    SnackbarHost(snackHost, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
                    navState.sheet?.let { id ->
                        if (id.startsWith("sess-menu:")) {
                            ModalSheetHost(false, { navigator.openSheet(null) }) {
                                SessionMenuSheet(id.removePrefix("sess-menu:"))
                            }
                        } else {
                            val expand = id == AppNavState.SHEET_TASK ||
                                id == AppNavState.SHEET_CUSTOM_FORM ||
                                id.startsWith(AppNavState.SHEET_CUSTOM_FORM + ":")
                            ModalSheetHost(expand, { navigator.openSheet(null) }) {
                                when (id) {
                                    AppNavState.SHEET_MODEL -> ModelSheet()
                                    AppNavState.SHEET_TASK -> TaskWizardSheet(
                                        onLaunch = { sessionId, desc, ctxRepo, ctxModel ->
                                            chatVm.sendMessage(desc, ctxRepo, ctxModel, sessionIdHint = sessionId)
                                        },
                                    )
                                    AppNavState.SHEET_GIT -> GitSheet()
                                    AppNavState.SHEET_CUSTOM_MODELS -> CustomModelsSheet()
                                    AppNavState.SHEET_UPDATE -> UpdateSheet()
                                    else -> {
                                        val prefix = AppNavState.SHEET_CUSTOM_FORM + ":"
                                        if (id.startsWith(prefix)) {
                                            CustomModelFormSheet(editId = id.removePrefix(prefix))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 底部弹层宿主:半透明遮罩 + 底部圆角面板。expand 时撑高到 90%。 */
@Composable
fun ModalSheetHost(expand: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current
    val noRipple = remember { MutableInteractionSource() }
    BoxWithConstraints(Modifier.fillMaxSize().background(t.backdrop)) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = noRipple, indication = null) { onDismiss() })
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(t.elev)
                .then(if (expand) Modifier.fillMaxHeight(0.9f) else Modifier.heightIn(max = maxHeight * 0.86f))
                .clickable(interactionSource = noRipple, indication = null) {},
        ) { content() }
    }
}

@Composable
private fun BottomBar(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val navState by navigator.state.collectAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(t.tabbar)
            .padding(horizontal = 26.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TabItem("会话", navState.route == AppNavState.ROUTE_HOME, navState.route != AppNavState.ROUTE_HOME) {
            navigator.navigate(AppNavState.ROUTE_HOME)
        }
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(t.accent)
                .clickable { navigator.openSheet(AppNavState.SHEET_TASK) },
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = t.accentInk, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        TabItem("设置", navState.route == AppNavState.ROUTE_SETTINGS, navState.route != AppNavState.ROUTE_SETTINGS) {
            navigator.navigate(AppNavState.ROUTE_SETTINGS)
        }
    }
}

@Composable
private fun TabItem(label: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 22.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = if (on) t.accent else t.text3, fontSize = 12.5.sp,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
    }
}
