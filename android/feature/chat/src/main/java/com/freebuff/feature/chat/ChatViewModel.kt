package com.freebuff.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.toApiError
import com.freebuff.core.data.repository.CatalogSource
import com.freebuff.core.data.repository.ChatRepository
import com.freebuff.core.data.repository.CustomModelRepository
import com.freebuff.core.data.repository.ModelCatalogRepository
import com.freebuff.core.data.repository.SessionRepository
import com.freebuff.core.data.repository.SettingsRepository
import com.freebuff.core.model.ChatMsg
import com.freebuff.core.model.ChatTarget
import com.freebuff.core.model.CustomModel
import com.freebuff.core.model.MsgStep
import com.freebuff.core.model.OfficialModel
import com.freebuff.core.model.Session
import com.freebuff.core.model.fmtT
import com.freebuff.core.model.mergedModelList
import com.freebuff.core.model.uid
import com.freebuff.core.ui.navigation.AppNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/**
 * 对话页:管理当前会话、输入框、真实 SSE 流式发送与停止。
 * 会话数据经 Room Flow 驱动,流式回复逐步写回占位消息。
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessionRepo: SessionRepository,
    private val customModelRepo: CustomModelRepository,
    private val catalog: ModelCatalogRepository,
    private val settings: SettingsRepository,
    private val chatRepo: ChatRepository,
    private val navigator: AppNavigator,
    @ApplicationContext private val context: Context,
    @Named("gatewayBaseUrl") private val gatewayBaseUrl: String,
) : ViewModel() {

    init {
        // 启动时后台尝试拉取官方目录:网关未配置则静默跳过,失败保留内置目录
        viewModelScope.launch { catalog.refreshIfNeeded() }
    }

    val sessions: StateFlow<List<Session>> = sessionRepo.sessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Eagerly 常驻订阅,保证任务向导提交后(对话页尚未组合时) value 已是最新会话。 */
    private val activeSessionId: StateFlow<String?> = navigator.state
        .map { it.activeSessionId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 当前打开的会话。 */
    val activeSession: StateFlow<Session?> = combine(sessions, activeSessionId) { ss, id ->
        ss.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val modelId: StateFlow<String> = settings.modelId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "deepseek-v4-flash")

    val customModels: StateFlow<List<CustomModel>> = customModelRepo.models
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 模型目录:官方(网关实时/内置回退)+ 自定义。 */
    val modelList: StateFlow<List<OfficialModel>> = combine(catalog.official, customModels) { official, customs ->
        mergedModelList(official, customs)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        mergedModelList(catalog.official.value, customModels.value),
    )

    /** 官方目录来源:用于面板提示是实时目录还是内置回退。 */
    val catalogSource: StateFlow<CatalogSource> = catalog.source

    /** 最近一次目录拉取失败原因(网关未配置时为 null)。 */
    val catalogError: StateFlow<ApiError?> = catalog.lastError

    /** 手动刷新官方目录(模型面板下拉/重试入口)。 */
    fun refreshCatalog() {
        viewModelScope.launch {
            when (val r = catalog.refresh()) {
                is com.freebuff.core.data.network.ApiResult.Ok ->
                    navigator.showSnack("官方目录已更新 · " + r.data.size + " 个模型")
                is com.freebuff.core.data.network.ApiResult.Err ->
                    navigator.showSnack(r.error.userMessage)
            }
        }
    }

    private val _chatInput = MutableStateFlow("")
    val chatInput: StateFlow<String> = _chatInput.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private var streamJob: Job? = null

    fun updateInput(v: String) { _chatInput.value = v }

    fun modelById(id: String): OfficialModel? = modelList.value.firstOrNull { it.id == id }

    fun setModel(id: String) {
        viewModelScope.launch {
            settings.setModelId(id)
            navigator.showSnack("已切换 " + (modelById(id)?.name ?: id))
        }
    }

    /** 复制代码到系统剪贴板。 */
    fun copyText(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("freebuff", text))
        navigator.showSnack("已复制")
    }

    /** 普通发送:把输入框内容发给当前会话。 */
    fun sendInput() {
        val text = _chatInput.value
        if (text.isBlank() || _streaming.value) return
        sendMessage(text.trim())
    }

    /** 发起一次对话。ctxRepo/ctxModel 由任务向导首条消息携带;sessionIdHint 用于向导刚创建、Flow 尚未传播的会话。 */
    fun sendMessage(text: String, ctxRepo: String = "", ctxModel: String = "", sessionIdHint: String? = null) {
        if (_streaming.value) return
        // 优先用向导传入的会话 id,避免 activeSession Flow 未更新导致首条消息丢失
        val sessionId = sessionIdHint ?: activeSession.value?.id ?: return
        val target = resolveTarget()
        _chatInput.value = ""
        val userMsg = ChatMsg(uid(), "user", text = text, time = fmtT(System.currentTimeMillis()),
            ctxRepo = ctxRepo, ctxModel = ctxModel)
        val agentMsg = ChatMsg(uid(), "agent", text = "", time = "正在生成",
            steps = listOf(MsgStep("连接", "连接模型并开始生成…")))
        viewModelScope.launch {
            val session = sessionRepo.get(sessionId) ?: return@launch
            sessionRepo.appendMessages(session.id, listOf(userMsg, agentMsg))
            _streaming.value = true
            try {
                if (!target.isConfigured) {
                    updateAgentText(session.id, "⚠ 尚未配置官方网关地址\n\n当前为演示构建,请在 app 模块的 buildConfigField 配置 DEFAULT_GATEWAY_BASE_URL,或改用自定义模型。")
                } else {
                    val history = buildHistory(session.id, ctxRepo)
                    streamJob = viewModelScope.launch {
                        chatRepo.chatStream(
                            endpoint = target.endpoint,
                            model = target.model,
                            apiKey = target.apiKey,
                            headers = target.headers,
                            skipTLS = target.skipTLS,
                            history = history,
                        ).collect { chunk ->
                            appendAgentText(session.id, chunk)
                        }
                    }
                    streamJob?.join()
                    finishAgent(session.id)
                }
            } catch (t: Throwable) {
                failAgent(session.id, t.toApiError().userMessage)
            } finally {
                _streaming.value = false
            }
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        viewModelScope.launch {
            activeSession.value?.let { s ->
                finishAgent(s.id)
            }
            _streaming.value = false
        }
    }

    /* ---------------- 内部实现 ---------------- */

    /** 解析当前模型对应的请求目标(自定义模型读其端点,官方模型走网关)。 */
    private fun resolveTarget(): ChatTarget {
        val id = modelId.value
        val custom = customModels.value.firstOrNull { it.id == id }
        return if (custom != null) {
            customModelRepo.chatTarget(custom)
        } else {
            ChatTarget(endpoint = gatewayBaseUrl, model = id, name = id)
        }
    }

    private suspend fun buildHistory(sessionId: String, ctxRepo: String): List<Pair<String, String>> {
        val msgs = sessionRepo.get(sessionId)?.messages.orEmpty()
            .filter { (it.role == "user" || it.role == "agent") && it.text.isNotBlank() }
        val history = mutableListOf<Pair<String, String>>()
        if (ctxRepo.isNotBlank()) history.add("system" to "本次任务关联仓库:$ctxRepo")
        history.addAll(msgs.map { it.role to it.text })
        return history
    }

    private suspend fun appendAgentText(sessionId: String, chunk: String) {
        val s = sessionRepo.get(sessionId) ?: return
        val last = s.messages.lastOrNull { it.role == "agent" } ?: return
        sessionRepo.replaceMessage(sessionId, last.id, last.copy(text = last.text + chunk))
    }

    private suspend fun updateAgentText(sessionId: String, text: String) {
        val s = sessionRepo.get(sessionId) ?: return
        val last = s.messages.lastOrNull { it.role == "agent" } ?: return
        sessionRepo.replaceMessage(sessionId, last.id, last.copy(text = text, time = fmtT(System.currentTimeMillis())))
    }

    private suspend fun finishAgent(sessionId: String) {
        val s = sessionRepo.get(sessionId) ?: return
        val last = s.messages.lastOrNull { it.role == "agent" } ?: return
        sessionRepo.replaceMessage(sessionId, last.id, last.copy(time = fmtT(System.currentTimeMillis())))
    }

    private suspend fun failAgent(sessionId: String, reason: String) {
        updateAgentText(sessionId, "⚠ 请求失败:$reason")
    }
}
