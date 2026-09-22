package com.freebuff.app

import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.AppNavigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局导航单例:route 驱动根页面,sheet 驱动底部弹层,activeSessionId 表示当前会话。
 * feature 层经 Hilt 注入同一实例,所有 ViewModel 共享一份状态。
 *
 * 弹层带一层返回目标([AppNavState.returnSheet]):从任务向导跳去 Git 授权后,
 * closeSheet 回到向导而不是直接落地——修复「授权完回不去向导」的断链。
 */
@Singleton
class AppNavigatorImpl @Inject constructor() : AppNavigator {

    private val _state = MutableStateFlow(AppNavState())
    override val state: StateFlow<AppNavState> = _state.asStateFlow()

    override fun navigate(route: String) {
        _state.update { it.copy(route = route, sheet = null, returnSheet = null) }
    }

    override fun openSession(sessionId: String) {
        _state.update { it.copy(activeSessionId = sessionId) }
    }

    override fun openSheet(sheet: String?, returnTo: String?) {
        _state.update { it.copy(sheet = sheet, returnSheet = returnTo) }
    }

    override fun closeSheet() {
        _state.update { s ->
            val back = s.returnSheet
            if (s.sheet != null && back != null && back != s.sheet) {
                // 回到来源弹层并清掉一次性返回目标
                s.copy(sheet = back, returnSheet = null)
            } else {
                s.copy(sheet = null, returnSheet = null)
            }
        }
    }

    override fun showSnack(message: String) {
        _state.update { it.copy(snackMessage = message) }
    }

    override fun consumeSnack() {
        _state.update { it.copy(snackMessage = null) }
    }
}
