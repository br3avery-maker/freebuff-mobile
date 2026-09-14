package com.freebuff.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freebuff.core.data.repository.SettingsRepository
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.AppNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 欢迎页:切换主题 / 登录或访客进入。 */
@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val navigator: AppNavigator,
) : ViewModel() {

    /** 点击 logo 循环切换主题。 */
    fun cycleTheme() {
        viewModelScope.launch {
            val cur = settings.getString(SettingsRepository.KEY_THEME_MODE, "dark")
            val next = when (cur) {
                "system" -> "light"
                "light" -> "dark"
                else -> "system"
            }
            settings.setThemeMode(next)
        }
    }

    fun enter(signedIn: Boolean) {
        viewModelScope.launch {
            settings.setSignedIn(signedIn)
            navigator.navigate(AppNavState.ROUTE_HOME)
        }
    }
}
