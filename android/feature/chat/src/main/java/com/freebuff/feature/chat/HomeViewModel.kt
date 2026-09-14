package com.freebuff.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freebuff.core.data.repository.CustomModelRepository
import com.freebuff.core.data.repository.ModelCatalogRepository
import com.freebuff.core.data.repository.SessionRepository
import com.freebuff.core.data.repository.SettingsRepository
import com.freebuff.core.model.Session
import com.freebuff.core.model.mergedModelList
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.AppNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 会话列表页:读取会话流,支持按标题/预览搜索,打开/删除会话。 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sessionRepo: SessionRepository,
    private val customModelRepo: CustomModelRepository,
    private val catalog: ModelCatalogRepository,
    private val settings: SettingsRepository,
    private val navigator: AppNavigator,
) : ViewModel() {

    /** 首页模型胶囊显示的当前模型名(官方实时/内置 + 自定义)。 */
    val modelName: StateFlow<String> = combine(
        catalog.official,
        customModelRepo.models,
        settings.modelId,
    ) { official, customs, id ->
        mergedModelList(official, customs).firstOrNull { it.id == id }?.name ?: id
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /** 是否处于演示构建(官方网关未配置):用于首页原型说明条。 */
    val isDemoBuild: Boolean get() = !catalog.isGatewayConfigured

    fun openModelSheet() { navigator.openSheet(AppNavState.SHEET_MODEL) }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 官方目录启动拉取:与对话页一致,网关未配置时静默跳过。 */
    init {
        viewModelScope.launch { catalog.refreshIfNeeded() }
    }

    fun updateQuery(q: String) { _query.value = q }

    /** 会话流 × 搜索词:标题或预览包含关键字即命中。 */
    val sessions: StateFlow<List<Session>> = combine(sessionRepo.sessions, _query) { list, q ->
        val kw = q.trim()
        if (kw.isEmpty()) list
        else list.filter {
            it.title.contains(kw, ignoreCase = true) ||
                it.preview.contains(kw, ignoreCase = true) ||
                it.messages.any { m -> m.text.contains(kw, ignoreCase = true) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun openChat(sessionId: String) {
        navigator.openSession(sessionId)
        navigator.navigate(AppNavState.ROUTE_CHAT)
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            sessionRepo.delete(sessionId)
            navigator.showSnack("已删除会话")
        }
    }
}
