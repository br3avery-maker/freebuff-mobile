package com.freebuff.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.ChatMessage
import com.freebuff.core.data.network.toApiError
import com.freebuff.core.data.repository.CatalogSource
import com.freebuff.core.data.repository.ChatRepository
import com.freebuff.core.data.repository.CustomModelRepository
import com.freebuff.core.data.repository.ModelCatalogRepository
import com.freebuff.core.data.repository.SessionRepository
import com.freebuff.core.data.repository.SettingsRepository
import com.freebuff.core.model.AgentEvent
import com.freebuff.core.model.ChatMsg
import com.freebuff.core.model.ChatTarget
import com.freebuff.core.model.DefaultTools
import com.freebuff.core.model.CustomModel
import com.freebuff.core.model.MsgStep
import com.freebuff.core.model.OfficialModel
import com.freebuff.core.model.Session
import com.freebuff.core.model.ToolCard
import com.freebuff.core.model.fmtT
import com.freebuff.core.model.mergedModelList
import com.freebuff.core.model.uid
import com.freebuff.core.ui.navigation.AppNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
    private val tools: com.freebuff.core.data.tools.ToolExecutors,
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

    // Eagerly 常驻订阅:向导提交后立刻 sendMessage 时这两个值必须已就绪,
    // WhileSubscribed 会在首页(聊天页未组合)期间停留在初始值,导致自定义模型被误判为官方模型
    val modelId: StateFlow<String> = settings.modelId
        .stateIn(viewModelScope, SharingStarted.Eagerly, "deepseek-v4-flash")

    val customModels: StateFlow<List<CustomModel>> = customModelRepo.models
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    /** 当前流式回复所属会话;停止时按它收尾,避免会话切换后落到错误会话。 */
    private var streamSessionId: String? = null

    /** 工具调用单次对话最多执行的轮数(模型→工具→模型 记一轮)。 */
    private companion object {
        const val MAX_TOOL_ROUNDS = 6
    }

    /** 工具开关(设置页「工具调用」):开启时请求携带工具定义,模型可触发 function calling。 */
    private val toolsEnabledState: StateFlow<Boolean> = settings.toolsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val toolsEnabled: Boolean
        get() = toolsEnabledState.value

    fun updateInput(v: String) { _chatInput.value = v }

    /** 切换会话时丢弃残留草稿:记录上一次输入归属,只有归属变化才清。 */
    private var inputOwnerSession: String? = null

    fun clearInputIfStale() {
        val cur = activeSession.value?.id
        if (cur != inputOwnerSession) {
            _chatInput.value = ""
            inputOwnerSession = cur
        }
    }

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
        val userMsg = ChatMsg(uid(), "user", text = text, time = fmtT(System.currentTimeMillis()),
            ctxRepo = ctxRepo, ctxModel = ctxModel)
        val agentMsg = ChatMsg(uid(), "agent", text = "", time = "正在生成",
            steps = listOf(MsgStep("连接", "连接模型并开始生成…")))
        viewModelScope.launch {
            val session = sessionRepo.get(sessionId) ?: return@launch
            // 会话确认存在后才清输入框,避免向导提交被会话失效吞字
            _chatInput.value = ""
            inputOwnerSession = session.id
            sessionRepo.appendMessages(session.id, listOf(userMsg, agentMsg))
            autoTitle(session.id, text)
            _streaming.value = true
            streamSessionId = session.id
            try {
                if (!target.isConfigured) {
                    updateAgentText(session.id, "⚠ 尚未配置官方网关地址\n\n当前为演示构建,请在 app 模块的 buildConfigField 配置 DEFAULT_GATEWAY_BASE_URL,或改用自定义模型。")
                } else {
                    // agent 循环:模型可请求工具(最多 MAX_TOOL_ROUNDS 轮),执行结果回传后继续生成
                    val msgs = buildHistory(session.id, ctxRepo)
                    var assistantPart = "" // 本轮 assistant 已输出的文本(回传时并入 tool_calls 消息)
                    var round = 0
                    while (true) {
                        val calls = runStreamRound(session.id, target, msgs, toolsJson = if (toolsEnabled) DefaultTools.toJsonArrayString() else "")
                            ?: break // 用户停止
                        // 流结束:若有工具调用且未超轮次,执行并回传,继续下一轮
                        if (calls.isEmpty() || !toolsEnabled || round >= MAX_TOOL_ROUNDS) {
                            if (calls.isNotEmpty() && round >= MAX_TOOL_ROUNDS) {
                                appendAgentText(session.id, "\n\n(工具调用轮次已达上限 $MAX_TOOL_ROUNDS,停止继续执行)")
                            }
                            break
                        }
                        round++
                        val toolMsgs = mutableListOf<ChatMessage>()
                        for (c in calls) {
                            val outcome = tools.execute(c)
                            completeToolCard(session.id, c.callId, outcome.content, outcome.isError)
                            toolMsgs += ChatMessage.toolResult(c.callId, outcome.content)
                        }
                        // 回传:assistant(含 tool_calls)+ 每个调用一条 tool 结果
                        msgs += ChatMessage.assistantWithCalls(assistantPart, calls)
                        msgs += toolMsgs
                        // 下一轮前把本会话最后一条 agent 消息的文本部分作为 assistant content 累积
                        assistantPart = ""
                    }
                    finishAgent(session.id)
                }
            } catch (t: Throwable) {
                // 用户主动停止已由 stopStreaming 收尾;只有真实失败才写错误文案
                if (t is CancellationException) throw t
                failAgent(session.id, t.toApiError().userMessage)
            } finally {
                if (streamSessionId == session.id) streamSessionId = null
                _streaming.value = false
            }
        }
    }

    /** 停止当前流式回复:取消请求并把「正在生成」落定;按发起流的那条会话收尾,而非当前激活会话。 */
    fun stopStreaming() {
        val sid = streamSessionId
        streamJob?.cancel()
        streamJob = null
        viewModelScope.launch {
            sid?.let { finishAgent(it) }
            _streaming.value = false
        }
    }

    /* ---------------- 内部实现 ---------------- */

    /**
     * 事件分派(agent-architecture.md §4):文本追加、工具卡片 upsert、错误归因。
     * 事件按到达顺序处理;工具串行执行保证 callId 成对有序,无需排序。
     * @return 本轮结束时的结构化工具调用(供循环执行);无调用返回空表
     */
    private suspend fun handleAgentEvent(sessionId: String, ev: AgentEvent): List<com.freebuff.core.model.ToolCallReq> =
        when (ev) {
            is AgentEvent.Text -> {
                appendAgentText(sessionId, ev.chunk); emptyList()
            }
            is AgentEvent.ToolCall -> {
                upsertToolCard(sessionId, ev.callId, ev.tool, ev.input); emptyList()
            }
            is AgentEvent.ToolResult -> {
                completeToolCard(sessionId, ev.callId, ev.output, ev.isError); emptyList()
            }
            is AgentEvent.Failure -> {
                appendAgentText(sessionId, "\n⚠ " + ev.message); emptyList()
            }
            is AgentEvent.SubagentChunk -> {
                upsertToolCard(
                    sessionId, "subagent-" + ev.agent,
                    ToolCard.SUBAGENT_PREFIX + ev.agent,
                    ev.chunk,
                ); emptyList()
            }
            is AgentEvent.Calls -> ev.calls
            AgentEvent.EndTurn -> emptyList()
        }

    /** 按呼叫 upsert 工具卡片:同名 callId 更新(OpenAI 参数增量多次到达),否则追加。 */
    private suspend fun upsertToolCard(sessionId: String, callId: String, tool: String, input: String) {
        val s = sessionRepo.get(sessionId) ?: return
        val last = s.messages.lastOrNull { it.role == "agent" } ?: return
        val cards = last.tools.toMutableList()
        val idx = cards.indexOfFirst { it.callId == callId }
        if (idx >= 0) cards[idx] = cards[idx].copy(tool = tool, input = input, state = "running")
        else cards += ToolCard(callId = callId, tool = tool, input = input, state = "running")
        sessionRepo.replaceMessage(sessionId, last.id, last.copy(tools = cards))
    }

    /** 工具收尾:写入输出与终态;无对应卡片时补一张(容错乱序)。 */
    private suspend fun completeToolCard(sessionId: String, callId: String, output: String, isError: Boolean) {
        val s = sessionRepo.get(sessionId) ?: return
        val last = s.messages.lastOrNull { it.role == "agent" } ?: return
        val cards = last.tools.toMutableList()
        val idx = cards.indexOfFirst { it.callId == callId }
        val updated = (cards.getOrNull(idx) ?: ToolCard(callId = callId, tool = "tool"))
            .copy(output = output, state = if (isError) "error" else "done")
        if (idx >= 0) cards[idx] = updated else cards += updated
        sessionRepo.replaceMessage(sessionId, last.id, last.copy(tools = cards))
    }

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

    private suspend fun buildHistory(sessionId: String, ctxRepo: String): MutableList<ChatMessage> {
        val s = sessionRepo.get(sessionId)
        val msgs = s?.messages.orEmpty()
            .filter { (it.role == "user" || it.role == "agent") && it.text.isNotBlank() }
        val history = mutableListOf<ChatMessage>()
        if (ctxRepo.isNotBlank()) history += ChatMessage.text("system", "本次任务关联仓库:$ctxRepo")
        history += ChatMessage.text(
            "system",
            "你是 Freebuff 助手。可使用提供的工具获取实时信息(联网搜索/GitHub/计算/时间);" +
                "回答保持简洁,工具结果仅供你参考加工。",
        )
        history += msgs.map { ChatMessage.text(it.role, it.text) }
        return history
    }

    /**
     * 跑一轮流式对话:收集事件、更新 UI,流结束后返回模型请求的结构化工具调用。
     * @return 调用列表(可为空);用户停止返回 null
     */
    private suspend fun runStreamRound(
        sessionId: String,
        target: ChatTarget,
        history: List<ChatMessage>,
        toolsJson: String,
    ): List<com.freebuff.core.model.ToolCallReq>? {
        var calls: List<com.freebuff.core.model.ToolCallReq> = emptyList()
        val job = viewModelScope.launch {
            try {
                chatRepo.chatStream(
                    endpoint = target.endpoint,
                    model = target.model,
                    apiKey = target.apiKey,
                    headers = target.headers,
                    skipTLS = target.skipTLS,
                    history = history,
                    toolsJson = toolsJson,
                ).collect { ev ->
                    handleAgentEvent(sessionId, ev)?.let { calls = it }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                failAgent(sessionId, t.toApiError().userMessage)
            }
        }
        streamJob = job
        job.join()
        // join 正常返回(非取消)时才视为完整一轮;用户 stop 会 cancel job → join 抛 CancellationException
        return if (job.isCancelled) null else calls
    }

    /** 首条用户消息后自动命名会话(取首行前 12 字);仅当仍是默认标题时生效。 */
    private suspend fun autoTitle(sessionId: String, firstUserText: String) {
        val s = sessionRepo.get(sessionId) ?: return
        if (s.title.isNotBlank() && s.title != "新对话") return
        val line = firstUserText.trim().lines().firstOrNull().orEmpty()
        if (line.isBlank()) return
        sessionRepo.updateTitle(sessionId, line.take(12) + if (line.length > 12) "…" else "")
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
        // 流结束时仍在 running 的卡片 = 未等到 tool_result(流提前结束/用户停止),标记未完成
        val cards = if (last.tools.any { it.isRunning }) {
            last.tools.map { if (it.isRunning) it.copy(state = "error", output = "(未完成)") else it }
        } else last.tools
        sessionRepo.replaceMessage(sessionId, last.id, last.copy(text = last.text, tools = cards, time = fmtT(System.currentTimeMillis())))
    }

    private suspend fun failAgent(sessionId: String, reason: String) {
        updateAgentText(sessionId, "⚠ 请求失败:$reason")
    }
}
