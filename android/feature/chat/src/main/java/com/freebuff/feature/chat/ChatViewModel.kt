package com.freebuff.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freebuff.core.data.context.ContextBuilder
import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.ChatMessage
import com.freebuff.core.data.network.RetryPolicy
import com.freebuff.core.data.network.toApiError
import com.freebuff.core.data.repository.ChatRepository
import com.freebuff.core.data.repository.CustomModelRepository
import com.freebuff.core.data.repository.MemoryEntryRepository
import com.freebuff.core.data.repository.MemoryRepository
import com.freebuff.core.data.repository.ModelCatalogRepository
import com.freebuff.core.data.repository.SessionRepository
import com.freebuff.core.data.repository.SettingsRepository
import com.freebuff.core.model.AgentEvent
import com.freebuff.core.model.ChatMsg
import com.freebuff.core.model.ChatTarget
import com.freebuff.core.model.ContextBudget
import com.freebuff.core.model.ContextPolicy
import com.freebuff.core.model.ContextStats
import com.freebuff.core.model.DefaultTools
import com.freebuff.core.model.CustomModel
import com.freebuff.core.model.MemoryCodec
import com.freebuff.core.model.MemoryExtraction
import com.freebuff.core.model.MemoryRecallCodec
import com.freebuff.core.model.MemoryStore
import com.freebuff.core.model.MsgStep
import com.freebuff.core.model.MsgSteps
import com.freebuff.core.model.OfficialModel
import com.freebuff.core.model.Session
import com.freebuff.core.model.Subagent
import com.freebuff.core.model.ToolCard
import com.freebuff.core.model.ToolPermission
import com.freebuff.core.model.ToolPermissions
import com.freebuff.core.model.ToolRepeatTracker
import com.freebuff.core.model.fmtT
import com.freebuff.core.model.mergedModelList
import com.freebuff.core.model.uid
import com.freebuff.core.ui.navigation.AppNavigator
import android.util.Log
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/**
 * 对话页:管理当前会话、输入框、真实 SSE 流式发送与停止。
 * 会话数据经 Room Flow 驱动,流式回复逐步写回占位消息。
 * 流式带空闲看门狗(连接存活但长时间不吐数据时自动停止,见 ChatRepository.idleWatchdog)。
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessionRepo: SessionRepository,
    private val customModelRepo: CustomModelRepository,
    private val catalog: ModelCatalogRepository,
    private val settings: SettingsRepository,
    private val chatRepo: ChatRepository,
    private val tools: com.freebuff.core.data.tools.ToolExecutors,
    private val memoryRepo: MemoryRepository,
    private val memoryEntryRepo: MemoryEntryRepository,
    private val contextBuilder: ContextBuilder,
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
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val customModels: StateFlow<List<CustomModel>> = customModelRepo.models
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 模型目录:官方(网关实时)+ 自定义。 */
    val modelList: StateFlow<List<OfficialModel>> = combine(catalog.official, customModels) { official, customs ->
        mergedModelList(official, customs)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        mergedModelList(catalog.official.value, customModels.value),
    )

    /** 官方网关是否已配置(未配置时官方模型列表为空,模型面板明确提示)。 */
    val gatewayConfigured: Boolean get() = catalog.isGatewayConfigured

    /** 是否已成功拉到过网关目录(用于面板区分「未配置」与「尚未拉取」)。 */
    val catalogLoaded: StateFlow<Boolean> = catalog.loaded

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

    private val _contextStats = MutableStateFlow<ContextStats?>(null)

    /** 最近一轮上下文构建统计:会话页据此提示「已压缩早期上下文」(见 docs/context-engineering.md §3)。 */
    val contextStats: StateFlow<ContextStats?> = _contextStats.asStateFlow()

    private var streamJob: Job? = null

    /** 当前流式回复所属会话;停止时按它收尾,避免会话切换后落到错误会话。 */
    private var streamSessionId: String? = null

    /** 被端点拒过工具定义的会话:该会话后续轮次不再附带 tools(降级为纯对话,不再反复碰壁)。 */
    private var toolsRejectedSession: String? = null

    private companion object {
        const val TAG = "ChatViewModel"

        /** 诊断日志开关:排查「后段消息不落库」这类流式写库时序问题时打开,定位后可关。 */
        const val LOG_DB_TRACE = true

        /** 比对正文/参数是否「同一段话」时用:忽略空白差异。 */
        val WHITESPACE_RE = Regex("\\s+")

        /** 记忆提取提示词:严格 JSON,便于端侧解析入库。 */
        const val EXTRACT_PROMPT = """从本轮用户消息中提取值得长期记住的信息:用户偏好与习惯、关键事实与约定、任务进度。
只记用户明确说出或确认过的内容;绝不要记录助手自己的推测、道歉、工具调用过程,也不要记「无记录/为空/未能找到」这类状态描述。
用户本轮没有提供新信息时,输出 []。
用 JSON 数组输出,元素形如 {"type":"long_term"|"short_term","content":"..."};偏好与事实用 long_term,任务进度用 short_term。
每条 content 写成一句简短陈述,且与用户语言一致(中文对话用中文),最多 5 条;没有值得记的就输出 []。只输出 JSON,不要解释。"""
    }

    /** 工具开关(设置页「工具调用」):开启时请求携带工具定义,模型可触发 function calling。 */
    private val toolsEnabledState: StateFlow<Boolean> = settings.toolsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val toolsEnabled: Boolean
        get() = toolsEnabledState.value

    /** 记忆开关(设置页「上下文记忆」):关闭时不注入记忆块、模型不可 save_memory。 */
    private val memoryEnabledState: StateFlow<Boolean> = settings.memoryEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val memoryEnabled: Boolean
        get() = memoryEnabledState.value

    /** 当前能力对应的工具集(记忆关闭时不暴露 save_memory / memory_recall)。 */
    private fun activeTools(): List<com.freebuff.core.model.AgentTool> =
        com.freebuff.core.model.DefaultTools.forCapabilities(memory = memoryEnabled)

    /** 深度思考(思维链)模式:off 不思考 / auto 按模型识别 / on 强制开启(设置页三态)。 */
    private val reasoningModeState: StateFlow<String> = settings.reasoningMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.freebuff.core.model.Reasoning.MODE_AUTO)

    private val reasoningMode: String
        get() = reasoningModeState.value

    /**
     * 按当前模型生成思维链请求参数。
     * 只送该模型所属族认得的字段;识别不了的模型在自动模式下不送(严格端点会对未知字段 400)。
     */
    private fun reasoningPlan(target: ChatTarget): com.freebuff.core.model.ReasoningPlan? =
        com.freebuff.core.model.Reasoning.plan(reasoningMode, target.model)

    /** 工具权限覆写(设置页可配置;生效分级 = 覆写 ∪ 默认)。 */
    private val permissionOverridesState: StateFlow<Map<String, ToolPermission>> =
        settings.toolPermissionOverrides.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** 挂起的确认请求:同一时刻至多一个(CONFIRM 级工具串行执行);deferred 即用户决定,await 真挂起。 */
    private class PendingConfirm(
        val req: com.freebuff.core.model.ToolCallReq,
        /** 发起该确认的会话:批准/拒绝时据此更新卡片 —— 不依赖可变的 streamSessionId。 */
        val sessionId: String,
        val deferred: kotlinx.coroutines.CompletableDeferred<Boolean>,
    )

    private val _pendingConfirmation = MutableStateFlow<PendingConfirm?>(null)

    /** 会话级权限记忆:「本次会话记住选择」——纯内存,会话切换/流结束即失效。 */
    private val sessionPermissionMemory = com.freebuff.core.model.SessionPermissionMemory()

    /** 对话页据此弹「是否允许执行该工具」确认框。 */
    val pendingConfirmation: StateFlow<com.freebuff.core.model.ToolCallReq?> =
        _pendingConfirmation.map { it?.req }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 用户批准当前挂起的工具调用:卡片回 running,门闩放行;勾选记住时写入会话记忆。 */
    fun approvePendingTool(remember: Boolean = false) {
        val p = _pendingConfirmation.value ?: return
        viewModelScope.launch { upsertToolCardState(p.sessionId, p.req.callId, "running") }
        if (remember) sessionPermissionMemory.remember(p.req.name, ToolPermission.ALLOW)
        p.deferred.complete(true)
        _pendingConfirmation.value = null
    }

    /** 用户拒绝当前挂起的工具调用:卡片标红,拒绝作为执行结果回传给模型;勾选记住时本会话内后续同工具直接拒绝。 */
    fun denyPendingTool(remember: Boolean = false) {
        val p = _pendingConfirmation.value ?: return
        if (remember) sessionPermissionMemory.remember(p.req.name, ToolPermission.DENY)
        p.deferred.complete(false)
        _pendingConfirmation.value = null
        viewModelScope.launch {
            completeToolCard(p.sessionId, p.req.callId, "(用户拒绝执行该工具)", isError = true)
        }
    }

    fun updateInput(v: String) { _chatInput.value = v }

    /** 切换会话时丢弃残留草稿:记录上一次输入归属,只有归属变化才清。 */
    private var inputOwnerSession: String? = null

    fun clearInputIfStale() {
        val cur = activeSession.value?.id
        if (cur != inputOwnerSession) {
            _chatInput.value = ""
            inputOwnerSession = cur
            _contextStats.value = null // 统计属于上一个会话,切换后不再展示
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
        val agentMsg = ChatMsg(uid(), "agent", text = "", time = MsgSteps.GENERATING,
            steps = listOf(MsgStep(MsgSteps.CONNECT, "连接模型并开始生成…")))
        viewModelScope.launch {
            val session = sessionRepo.get(sessionId) ?: return@launch
            // 会话确认存在后才清输入框,避免向导提交被会话失效吞字
            _chatInput.value = ""
            inputOwnerSession = session.id
            sessionRepo.appendMessages(session.id, listOf(userMsg, agentMsg))
            autoTitle(session.id, text)
            // 整个对话操作包进一个可取消子协程,句柄存进 streamJob:
            // stopStreaming 取消它时,LLM 摘要压缩/上下文构建/重试退避等所有阶段一并中断 ——
            // 只取消单个流式 Job 时,用户停止后操作可能仍在后台继续写库(僵尸协程),
            // 把后续答案写进下一次发送的占位消息(即「轮次停止后 agent 消息缺失」的根因)
            val opJob = launch {
                if (LOG_DB_TRACE) Log.i(TAG, "op begin sid=${session.id.takeLast(6)} msg=${agentMsg.id.takeLast(6)}")
                // 本次流式操作归属的会话:停止/确认回写都靠它定位(此处必须赋值)
                streamSessionId = session.id
                _streaming.value = true
                // 连续失败计数每轮重置:它只用来在同一轮内从「按建议重试」升级为「换路」
                toolFailStreak.clear()
                try {
                    if (!target.isConfigured) {
                        updateAgentText(session.id, noModelMessage())
                    } else {
                        // agent 循环:每轮由 ContextBuilder 重建上下文(记忆+预算)。
                        // 延续策略见 core:model AgentLoop —— 还在调工具就继续;动过工具却只回文本(进度播报)
                        // 时提醒继续;模型调用 task_completed 才算完成;轮次上限只是兜底。
                        var toolRounds = 0
                        var nudges = 0
                        var userStopped = false // 本轮是被用户停止中断的(收尾要给停止标注)
                        var repeatRounds = 0 // 连续「整轮都在重复调用」的轮数
                        var lastRoundText = "" // 上一轮已写进正文的文本(用于去掉重复追加)
                        val repeats = ToolRepeatTracker()
                        val protocol = mutableListOf<ChatMessage>() // 跨轮累积:assistant(tool_calls)+tool 结果(严格协议)
                        while (true) {
                            val roundText = StringBuilder() // 本轮流式输出的文本(回传时作为 assistant content)
                            if (LOG_DB_TRACE) Log.i(TAG, "round ${toolRounds + 1} start sid=${session.id.takeLast(6)}")
                            // 状态牌:让用户看到在第几轮、在做什么(实测多轮工具调用时界面会长时间无反馈)
                            setProgressStep(session.id, MsgSteps.round(toolRounds + 1), MsgSteps.calling(emptyList()))
                            // 工具可用性:用户开关 + 本会话是否已被端点拒过工具(拒绝后本轮继续纯对话,不再反复碰壁)。
                            // 先算出来再建上下文:系统提示里的「工具使用约定」要与本轮是否真的附带 tools 一致
                            val useTools = toolsEnabled && toolsRejectedSession != session.id
                            val base = buildHistory(session.id, ctxRepo, target, useTools)
                            val calls = runStreamRound(
                                session.id, target, base + protocol,
                                toolsJson = if (useTools) DefaultTools.toJsonArrayString(activeTools()) else "",
                                textSink = roundText,
                            )
                            if (calls == null) {
                                // 用户停止:外层操作协程已随之被取消,这里只是取消前的常规出口。
                                // 这里同样要带上「用户停止」:两条收尾路径的执行顺序不定(实测相差 30ms),
                                // 否则后跑的那条会把已经写好的停止标注覆盖成空正文。
                                userStopped = true
                                if (LOG_DB_TRACE) Log.i(TAG, "round stopped-by-user sid=${session.id.takeLast(6)} round=${toolRounds + 1}")
                                break
                            }
                            val completed = calls.any { it.name in com.freebuff.core.model.AgentLoop.END_TOOLS }
                            var repeatedThisRound = 0
                            val roundDigest = mutableListOf<Pair<String, String>>() // 本轮的「工具 → 结果首行」
                            if (calls.isNotEmpty()) {
                                toolRounds++
                                // 执行工具;结果写入工具卡片(UI)+ 压缩后进入协议消息(下一轮请求)
                                protocol += ChatMessage.assistantWithCalls(roundText.toString(), calls)
                                val calledNames = mutableListOf<String>()
                                for (c in calls) {
                                    // 调用轨迹:记工具名 + 原始参数 JSON。卡片里存的是给人看的参数摘要
                                    // (query/url/path 这类只剩值、丢了键名),回归脚本读卡片分不清
                                    // 「参数名写错」和「摘要只留了值」,所以这里留一行原样可解析的记录。
                                    if (LOG_DB_TRACE) Log.i(
                                        TAG,
                                        "tool req sid=${session.id.takeLast(6)} round=$toolRounds " +
                                            "name=${c.name} args=${c.argsJson.take(200)}",
                                    )
                                    val sig = repeats.signature(c)
                                    val cached = repeats.cachedOutput(sig)
                                    val outcome = if (cached != null) {
                                        // 完全相同的调用:复用上次结果,不重新执行(某些模型会把同一组工具整轮重放)。
                                        // 结果虽已复用,仍要告诉模型「别重发」——模型重放是本项目实测过的主要循环之一。
                                        repeatedThisRound++
                                        com.freebuff.core.model.ToolOutcome(
                                            com.freebuff.core.model.ToolErrors.duplicateNote(c.name, cached),
                                        )
                                    } else {
                                        dispatchTool(session.id, c).also { r ->
                                            // 连续失败计数:同一工具第 2 次失败起,错误信封会劝它换路而不是继续重试
                                            if (r.isError) toolFailStreak[c.name] = (toolFailStreak[c.name] ?: 0) + 1
                                            else toolFailStreak.remove(c.name)
                                            repeats.remember(sig, r.content)
                                        }
                                    }
                                    completeToolCard(
                                        session.id, c.callId, outcome.content, outcome.isError,
                                        reused = cached != null,
                                    )
                                    protocol += ChatMessage.toolResult(c.callId, ContextPolicy.compressToolResult(outcome.content))
                                    calledNames += c.name
                                    roundDigest += com.freebuff.core.model.toolDisplayName(c.name) to outcome.content
                                }
                                setProgressStep(session.id, MsgSteps.round(toolRounds), MsgSteps.calling(calledNames))
                                if (repeatRounds > 0 || repeatedThisRound > 0) {
                                    Log.i(TAG, "repeat sid=${session.id.takeLast(6)} round=$toolRounds dup=$repeatedThisRound/${calls.size} consecutive=$repeatRounds")
                                }
                            }
                            // 整轮都是重复调用才算「原地打转」;换参数/换工具都算新进展
                            repeatRounds = if (calls.isNotEmpty() && repeatedThisRound == calls.size) repeatRounds + 1 else 0
                            // 模型重放时会把同一段话再吐一遍:去掉刚追加进去的重复段落
                            val roundStr = roundText.toString()
                            if (roundStr.isNotBlank()) {
                                if (normText(roundStr) == normText(lastRoundText)) {
                                    removeTrailingText(session.id, roundStr)
                                } else {
                                    lastRoundText = roundStr
                                }
                            }
                            val decision = com.freebuff.core.model.AgentLoop.decide(
                                toolRounds = toolRounds,
                                toolCalls = calls.size,
                                toolsEnabled = useTools,
                                nudgesUsed = nudges,
                                completed = completed,
                                repeatRounds = repeatRounds,
                            )
                            when (decision) {
                                com.freebuff.core.model.AgentLoop.Decision.Continue -> Unit
                                com.freebuff.core.model.AgentLoop.Decision.Stop -> break
                                is com.freebuff.core.model.AgentLoop.Decision.StopWithNote -> {
                                    // 模型重放时常常一个字也不说就停了:把本轮的执行结果附在提示里,
                                    // 否则用户看到的「回复」就是空卡片 + 一句停因(实测存在)
                                    val note = "\n\n" + decision.note + MsgSteps.digest(roundDigest)
                                    appendAgentText(session.id, note)
                                    break
                                }
                                is com.freebuff.core.model.AgentLoop.Decision.Nudge -> {
                                    // 只进协议消息,不进正文:下一轮模型会看到「继续推进」的要求
                                    nudges++
                                    if (LOG_DB_TRACE) Log.i(TAG, "nudge #$nudges sid=${session.id.takeLast(6)}")
                                    // 角色用 user:中途插 system 在 OpenAI 兼容网关上行为不一(见 AgentLoop.REMINDER_ROLE)
                                    protocol += ChatMessage.text(
                                        com.freebuff.core.model.AgentLoop.REMINDER_ROLE,
                                        decision.message,
                                    )
                                }
                            }
                        }
                        finishAgent(session.id, stoppedByUser = userStopped)
                        if (LOG_DB_TRACE) Log.i(TAG, "op finish sid=${session.id.takeLast(6)} rounds=$toolRounds")
                    }
                } catch (t: Throwable) {
                    // 用户主动停止已由 stopStreaming 收尾;只有真实失败才写错误文案
                    if (t is CancellationException) {
                        if (LOG_DB_TRACE) Log.i(TAG, "op cancelled sid=${session.id.takeLast(6)} (user stop or VM clear)")
                        throw t
                    }
                    Log.w(TAG, "op failed sid=${session.id.takeLast(6)}: ${t.message}")
                    failAgent(session.id, t.toApiError().userMessage)
                } finally {
                    if (streamSessionId == session.id) streamSessionId = null
                    _streaming.value = false
                    // 流结束(含被取消)时丢弃未决的确认弹窗与会话记忆,避免残留遮罩/记忆泄到下一轮会话语境
                    _pendingConfirmation.value = null
                    sessionPermissionMemory.clear()
                }
                // 轮次结束后的记忆提取:独立协程执行 —— 它是额外一次 LLM 调用,
                // 不能让「正在生成」状态与输入框被它拖住(提取失败静默)。
                // 同时只允许一次在跑:慢端点下连发多轮时,后台提取会堆积并把前台请求拖慢
                launch {
                    if (!extractionLock.tryLock()) return@launch
                    try {
                        extractTurnMemories(session.id, target)
                    } finally {
                        extractionLock.unlock()
                    }
                }
            }
            streamJob = opJob
            opJob.join()
        }
    }

    /** 停止当前对话操作:取消整个操作协程(含压缩/重试等非流式阶段)并把「正在生成」落定;
     *  按发起流的那条会话收尾,而非当前激活会话。 */
    fun stopStreaming() {
        val sid = streamSessionId
        Log.i(TAG, "stop requested sid=${sid?.takeLast(6)} job=${streamJob != null}")
        streamJob?.cancel()
        streamJob = null
        viewModelScope.launch {
            sid?.let { finishAgent(it, stoppedByUser = true) }
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
                // 边收边洗:端点会把 chat 模板控制词(<|eos|> 等)当普通文本漏进正文,
                // 写入/显示前就去掉(收尾还会再洗一次,兼顾被拆到两个 chunk 的半个词)
                val clean = com.freebuff.core.model.OutputSanitizer.clean(ev.chunk)
                if (clean.isNotEmpty()) appendAgentText(sessionId, clean)
                emptyList()
            }
            is AgentEvent.Reasoning -> {
                // 思考(思维链)增量:单独存一列并折叠展示,不进正文、不进下一轮上下文
                appendAgentReasoning(sessionId, ev.chunk); emptyList()
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
            is AgentEvent.Notice -> {
                // 适配层提示(端点不吃工具定义/思考字段):原样告知用户,并记下本会话的降级状态
                when (ev.kind) {
                    AgentEvent.Notice.Kind.TOOLS_DROPPED -> toolsRejectedSession = sessionId
                    AgentEvent.Notice.Kind.REASONING_DROPPED -> Unit
                    AgentEvent.Notice.Kind.INFO -> Unit
                }
                navigator.showSnack(ev.message)
                emptyList()
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

    /** 工具收尾:写入输出与终态;无对应卡片时补一张(容错乱序)。reused = 结果复用(未真正执行)。 */
    private suspend fun completeToolCard(
        sessionId: String,
        callId: String,
        output: String,
        isError: Boolean,
        reused: Boolean = false,
    ) {
        val s = sessionRepo.get(sessionId) ?: return
        val last = s.messages.lastOrNull { it.role == "agent" } ?: return
        val cards = last.tools.toMutableList()
        val idx = cards.indexOfFirst { it.callId == callId }
        val updated = (cards.getOrNull(idx) ?: ToolCard(callId = callId, tool = "tool"))
            .copy(output = output, state = when { reused -> "reused"; isError -> "error"; else -> "done" })
        if (idx >= 0) cards[idx] = updated else cards += updated
        sessionRepo.replaceMessage(sessionId, last.id, last.copy(tools = cards))
    }

    /** 解析当前模型对应的请求目标(自定义模型读其端点,官方模型走网关)。 */
    /**
     * 「没有可用模型」的操作指引:按缺的是「选择」还是「配置」给不同出路,
     * 避免用户按提示添加了自定义模型却发现真正原因是从没选过模型。
     */
    private fun noModelMessage(): String {
        val picked = modelId.value.isNotBlank()
        val hasCustom = customModels.value.isNotEmpty()
        return when {
            !picked && hasCustom ->
                "⚠ 还没有选择模型\n\n点上方模型条选择一个模型再发送。"
            hasCustom ->
                "⚠ 当前模型不可用\n\n它可能已被删除或缺少端点配置,请在模型列表中选择其他模型。"
            else ->
                "⚠ 当前没有可用模型\n\n请到 设置 → 自定义模型 添加一个 OpenAI 兼容端点,再在模型列表中选择它。\n(官方模型需要在构建时配置官方网关地址)"
        }
    }

    private fun resolveTarget(): ChatTarget {
        val id = modelId.value
        val custom = customModels.value.firstOrNull { it.id == id }
        return if (custom != null) {
            customModelRepo.chatTarget(custom)
        } else {
            ChatTarget(endpoint = gatewayBaseUrl, model = id, name = id)
        }
    }

    /**
     * 构建请求上下文:记忆块 + 会话历史,经 ContextBuilder 预算裁剪与压缩。
     * 每轮循环重建一次 —— 工具结果落库后,下一轮自动进入上下文。
     */
    private suspend fun buildHistory(
        sessionId: String,
        ctxRepo: String,
        target: ChatTarget,
        toolsEnabled: Boolean = true,
    ): List<ChatMessage> {
        val s = sessionRepo.get(sessionId)
        val msgs = s?.messages.orEmpty().dropLast(1) // 最后一条是占位 agent 消息,不进上下文
        val budget = ContextBudget(contextWindow = ContextPolicy.parseCtxWindow(target.ctxWindow))
        // 工作记忆:按最新用户输入检索 Top K 历史条目,注入 system prompt(检索失败不影响对话)
        val workingMemory = if (memoryEnabled) {
            val query = msgs.lastOrNull { it.role == "user" }?.text.orEmpty()
            if (query.isBlank()) ""
            else runCatching {
                MemoryRecallCodec.formatForPrompt(
                    memoryEntryRepo.search(query, topK = MemoryStore.DEFAULT_TOP_K, bumpHits = false),
                )
            }.getOrDefault("")
        } else ""
        // 摘要缓存:同一会话内被裁段没明显变多就复用上次的摘要,不再重复调 LLM 摘要
        val built = contextBuilder.build(
            memoryBlocks = if (memoryEnabled) memoryRepo.load() else emptyList(),
            session = msgs,
            budget = budget,
            summarize = llmSummarizer(target),
            workingMemory = workingMemory,
            userId = MemoryStore.LOCAL_USER_ID,
            cachedSummary = summaryCache[sessionId],
            toolsEnabled = toolsEnabled,
        )
        built.summary?.let {
            summaryCache[sessionId] = com.freebuff.core.data.context.CachedSummary(built.summaryCoverage, it)
        }
        _contextStats.value = built.stats
        val out = built.messages.toMutableList()
        if (ctxRepo.isNotBlank()) {
            // 任务向导携带的仓库上下文:追加进首条 system 提示,而不是插一条新的 system 消息
            // (OpenAI 兼容网关惯例把 system 当首条,中途再插一条行为不一)
            out[0] = ChatMessage.text(
                "system",
                out[0].content + "\n\n本次任务关联仓库:" + ctxRepo,
            )
        }
        return out
    }

    /** 会话 → 最近一次生成的早期上下文摘要(长会话里避免每轮都重新摘要)。 */
    private val summaryCache = HashMap<String, com.freebuff.core.data.context.CachedSummary>()

    /**
     * 工具名 → 本轮会话内连续失败次数。
     * 用途只有一个:同一工具第 2 次失败时,错误信封从「按建议重试」升级为「换路或告知用户」——
     * 弱模型很容易拿着同一个错参数反复撞(业界共识是有界自纠:重试一两次就应该换策略)。
     */
    private val toolFailStreak = HashMap<String, Int>()

    /** 记忆提取串行锁:同一时刻只跑一次,已在跑则跳过本轮(记忆是尽力而为)。 */
    private val extractionLock = kotlinx.coroutines.sync.Mutex()

    /**
     * LLM 摘要压缩执行器(LibreChat 式):把被裁掉的早期消息交给模型生成摘要。
     * 复用流式管道收集文本;失败由调用方回退提取式摘要。
     */
    private fun llmSummarizer(target: ChatTarget): (suspend (List<com.freebuff.core.data.context.ContextTurn>, String?) -> String)? {
        if (!target.isConfigured) return null
        return { turns, previous ->
            val convo = turns.joinToString("\n") { t ->
                (if (t.role == "agent") "助手" else "用户") + ":" +
                    t.text.take(800) +
                    t.toolOutputs.joinToString("") { (tool, pair) -> "\n[" + tool + " 结果] " + pair.second.take(300) }
            }
            val sb = StringBuilder()
            chatRepo.chatStream(
                endpoint = target.endpoint,
                model = target.model,
                apiKey = target.apiKey,
                headers = target.headers,
                skipTLS = target.skipTLS,
                history = listOf(
                    ChatMessage.text(
                        "system",
                        "把以下对话压缩成简洁摘要,保留:用户的最终目标、已确定的关键决策、重要数据与结论、尚未完成的事项。" +
                            "若已给出【已有摘要】,它是更早部分的压缩,请把它与新内容合并成一份连贯摘要。直接输出摘要正文,不要客套。",
                    ),
                    ChatMessage.text(
                        "user",
                        (if (previous.isNullOrBlank()) "" else "【已有摘要】\n" + previous.take(4000) + "\n\n") +
                            "【需要压缩的对话】\n" + convo.take(24000),
                    ),
                ),
            ).collect { ev ->
                if (ev is com.freebuff.core.model.AgentEvent.Text) sb.append(ev.chunk)
            }
            sb.toString().trim()
        }
    }

    /**
     * 端侧工具分派:先过权限层(allow 静默执行 / confirm 挂起等用户决定 / deny 直接拒绝),
     * 再按工具类型执行 —— 记忆类走本地仓库,其余交给 ToolExecutors。
     */
    private suspend fun dispatchTool(
        sessionId: String,
        c: com.freebuff.core.model.ToolCallReq,
    ): com.freebuff.core.model.ToolOutcome {
        // 这是该工具连续第几次失败(第 2 次起，信封从「按建议重试」升级为「换路」)
        val attempt = (toolFailStreak[c.name] ?: 0) + 1
        // 当前会话真正可用的工具集:关掉记忆能力时不含记忆工具,未知名字的建议据此生成
        val available = activeTools()
        // 占位分派(P0 协议层已注册工具,执行引擎未上线):不执行、不弹权限确认,
        // 直接回填说明文本让模型自行完成子任务;同时预热模型的调用形态,P1 上线后无缝切换
        if (c.name == Subagent.TOOL_NAME) {
            return com.freebuff.core.model.ToolOutcome(Subagent.comingSoonMessage())
        }
        // 未知工具名直接回错误结果(带近邻建议):它无法执行,弹确认只会卡住循环、白点一次「允许」(实测浏览工具幻觉)
        if (!com.freebuff.core.model.DefaultTools.isKnown(c.name, available)) {
            return com.freebuff.core.model.ToolOutcome(
                com.freebuff.core.model.ToolErrors.unknownTool(
                    c.name, com.freebuff.core.model.DefaultTools.names(available),
                ),
                isError = true,
            )
        }
        when (ToolPermissions.effective(c.name, permissionOverridesState.value)) {
            ToolPermission.DENY ->
                return com.freebuff.core.model.ToolOutcome(
                    com.freebuff.core.model.ToolErrors.disabledByUser(c.name), isError = true,
                )
            ToolPermission.CONFIRM -> {
                // 会话内已记住本工具的决定(「本次会话记住选择」):免弹窗直接按记忆执行/拒绝
                sessionPermissionMemory.get(c.name)?.let { remembered ->
                    if (remembered == ToolPermission.DENY) {
                        return com.freebuff.core.model.ToolOutcome(
                            com.freebuff.core.model.ToolErrors.deniedByUser(c.name), isError = true,
                        )
                    }
                }
                // 卡片置 waiting;await 真挂起等用户决定,停止对话(取消协程)会直接中断。
                // 会话 id 由调用方传入:曾依赖可变的 streamSessionId,一旦漏赋值,
                // 所有「需确认」工具都会以「会话已关闭」失败、确认弹窗永不出现。
                upsertToolCardState(sessionId, c.callId, "waiting")
                val pending = PendingConfirm(c, sessionId, kotlinx.coroutines.CompletableDeferred())
                _pendingConfirmation.value = pending
                val granted = pending.deferred.await()
                if (!granted) {
                    return com.freebuff.core.model.ToolOutcome(
                        com.freebuff.core.model.ToolErrors.deniedByUser(c.name), isError = true,
                    )
                }
            }
            ToolPermission.ALLOW -> Unit
        }
        return when (c.name) {
            "save_memory" -> executeSaveMemory(c, attempt)
            "memory_recall" -> executeMemoryRecall(c, attempt)
            else -> tools.execute(c, available, attempt)
        }
    }

    /** 把指定工具卡片的状态置为 waiting/running(权限确认流用)。 */
    private suspend fun upsertToolCardState(sessionId: String, callId: String, state: String) {
        val s = sessionRepo.get(sessionId) ?: return
        val last = s.messages.lastOrNull { it.role == "agent" } ?: return
        val cards = last.tools.map { if (it.callId == callId) it.copy(state = state) else it }
        sessionRepo.replaceMessage(sessionId, last.id, last.copy(tools = cards))
    }

    /**
     * memory_recall 工具端侧执行:按 user_id/query/top_k/memory_type 检索记忆库。
     * 参数缺失或非法时回传明确错误(模型可修正后重试);非本机 user_id 命中为空时回退本机记忆。
     */
    private suspend fun executeMemoryRecall(
        c: com.freebuff.core.model.ToolCallReq,
        attempt: Int = 1,
    ): com.freebuff.core.model.ToolOutcome {
        val a = MemoryRecallCodec.parse(c.argsJson)
        val recallTool = com.freebuff.core.model.DefaultTools.find("memory_recall")
        val example = recallTool?.example.orEmpty()
        if (a.userId.isBlank()) {
            return com.freebuff.core.model.ToolOutcome(
                com.freebuff.core.model.ToolErrors.report(
                    com.freebuff.core.model.ToolErrors.Kind.MISSING_PARAM, "memory_recall",
                    "缺少 user_id", "user_id 填系统提示里给出的当前用户 id", attempt, example,
                ),
                isError = true,
            )
        }
        if (a.query.isBlank()) {
            return com.freebuff.core.model.ToolOutcome(
                com.freebuff.core.model.ToolErrors.report(
                    com.freebuff.core.model.ToolErrors.Kind.MISSING_PARAM, "memory_recall",
                    "缺少 query", "query 写你要查的事(如「回答长度偏好」)", attempt, example,
                ),
                isError = true,
            )
        }
        return try {
            var hits = memoryEntryRepo.search(a.query, a.topK, a.memoryType, userId = a.userId)
            var note = ""
            if (hits.isEmpty() && a.userId != MemoryStore.LOCAL_USER_ID) {
                // 访客模式单用户:模型传了别的 user_id 时回退本机记忆,避免检索永远为空
                hits = memoryEntryRepo.search(a.query, a.topK, a.memoryType, userId = MemoryStore.LOCAL_USER_ID)
                if (hits.isNotEmpty()) note = "\n(user_id=${a.userId} 无记录,已回退本机记忆)"
            }
            if (hits.isEmpty()) {
                // 词法检索也可能因说法/语言差异(如中文查询 ↔ 英文记忆)零命中:
                // 此时给「最近记忆」兜底并明确标注不是直接匹配,避免模型误以为记忆库为空
                hits = memoryEntryRepo.search("", a.topK, a.memoryType, userId = MemoryStore.LOCAL_USER_ID, bumpHits = false)
                if (hits.isNotEmpty()) note = "\n(无直接匹配,以下为最近记忆,引用时请说明这是推测)"
            }
            com.freebuff.core.model.ToolOutcome(MemoryRecallCodec.formatResult(a.userId, a.query, hits) + note)
        } catch (e: Exception) {
            com.freebuff.core.model.ToolOutcome("检索记忆失败:" + (e.message ?: e::class.java.simpleName), isError = true)
        }
    }

    /**
     * 轮次结束后的记忆提取(工作流第 3 步):让模型抽取本轮值得长期保留的信息,
     * 按 long_term/short_term 分类入库,供后续检索注入。失败静默,不影响对话。
     */
    private suspend fun extractTurnMemories(sessionId: String, target: ChatTarget) {
        if (!memoryEnabled || !target.isConfigured) return
        try {
            val s = sessionRepo.get(sessionId) ?: return
            val user = s.messages.lastOrNull { it.role == "user" } ?: return
            val agent = s.messages.lastOrNull { it.role == "agent" } ?: return
            if (user.text.isBlank() || agent.text.isBlank()) return
            if (agent.text.trim().startsWith("⚠")) return // 失败的一轮没有可提信息(错误文案不作记忆源;trim 兼容追加式错误文案)
            val sb = StringBuilder()
            chatRepo.chatStream(
                endpoint = target.endpoint,
                model = target.model,
                apiKey = target.apiKey,
                headers = target.headers,
                skipTLS = target.skipTLS,
                history = listOf(
                    ChatMessage.text("system", EXTRACT_PROMPT),
                    ChatMessage.text("user", "用户:" + user.text.take(2000) + "\n助手:" + agent.text.take(3000)),
                ),
            ).collect { ev -> if (ev is AgentEvent.Text) sb.append(ev.chunk) }
            val items = MemoryExtraction.parse(sb.toString())
            if (items.isNotEmpty()) {
                memoryEntryRepo.addExtracted(items, sessionId = sessionId, userId = MemoryStore.LOCAL_USER_ID)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // 提取失败不影响本轮对话
        }
    }

    /** save_memory 工具端侧执行:写入记忆块,返回给模型的确认文案。 */
    private suspend fun executeSaveMemory(
        c: com.freebuff.core.model.ToolCallReq,
        attempt: Int = 1,
    ): com.freebuff.core.model.ToolOutcome {
        val a = MemoryCodec.parseSaveArgs(c.argsJson)
        return try {
            com.freebuff.core.model.ToolOutcome(memoryRepo.applySave(a.block, a.content, a.replace))
        } catch (e: Exception) {
            com.freebuff.core.model.ToolOutcome(
                com.freebuff.core.model.ToolErrors.internalError(
                    "save_memory",
                    "保存记忆失败:" + (e.message ?: e::class.java.simpleName),
                    attempt,
                ),
                isError = true,
            )
        }
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
        textSink: StringBuilder? = null,
    ): List<com.freebuff.core.model.ToolCallReq>? {
        var calls: List<com.freebuff.core.model.ToolCallReq> = emptyList()
        val job = viewModelScope.launch {
            var retry = 0
            while (true) {
                try {
                    chatRepo.chatStream(
                        endpoint = target.endpoint,
                        model = target.model,
                        apiKey = target.apiKey,
                        headers = target.headers,
                        skipTLS = target.skipTLS,
                        history = history,
                        toolsJson = toolsJson,
                        reasoning = reasoningPlan(target),
                    ).collect { ev ->
                        if (ev is com.freebuff.core.model.AgentEvent.Text) textSink?.append(ev.chunk)
                        handleAgentEvent(sessionId, ev)?.let { calls = it }
                    }
                    break // 本轮流完整结束
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    // 本轮已有流式输出:半截内容 + 重新生成 = 重复文本,不重试,走收尾提示
                    if (textSink?.isNotEmpty() == true) {
                        if (t is ApiError.StreamIdle) {
                            appendAgentText(sessionId, "\n\n⚠ 超过 " + (t.idleMs / 1000) + " 秒没有收到新的流式数据,已自动停止生成;以上为已收到的部分回复。")
                        } else {
                            failAgent(sessionId, t.toApiError().userMessage)
                        }
                        break
                    }
                    // 瞬时失败(网络抖动/限流/5xx)自动重试;确定性失败(鉴权/参数/TLS)重试必然再败,直接报错
                    if (!RetryPolicy.isRetryable(t) || retry >= RetryPolicy.MAX_RETRIES) {
                        failAgent(sessionId, t.toApiError().userMessage)
                        break
                    }
                    retry++
                    recordRetryAttempt(sessionId, retry, t.toApiError().userMessage)
                    // 退避等待(指数):期间用户停止则放弃重试
                    if (!RetryPolicy.waitBackoff(retry) { !_streaming.value }) break
                }
            }
        }
        streamJob = job
        job.join()
        // join 正常返回(非取消)时才视为完整一轮;用户 stop 会 cancel job → join 抛 CancellationException
        return if (job.isCancelled) null else calls
    }

    /** 把一次自动重试写进消息:steps 追加「重试 n」步骤,time 位显示尝试进度(流式期间的状态牌,成功后由 finishAgent 落定真实时间)。 */
    private suspend fun recordRetryAttempt(sessionId: String, retry: Int, lastError: String) {
        val s = sessionRepo.get(sessionId) ?: return
        val last = s.messages.lastOrNull { it.role == "agent" } ?: return
        sessionRepo.replaceMessage(
            sessionId, last.id,
            last.copy(
                time = "⟳ 第 " + (retry + 1) + "/" + (RetryPolicy.MAX_RETRIES + 1) + " 次尝试",
                steps = last.steps + MsgStep(MsgSteps.retry(retry), "上次失败:" + lastError + ";退避后自动重试"),
            ),
        )
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

    /** 追加思考(思维链)内容:单独存储,不混进正文(复制正文/上下文重建都不带它)。 */
    private suspend fun appendAgentReasoning(sessionId: String, chunk: String) {
        val s = sessionRepo.get(sessionId) ?: return
        val last = s.messages.lastOrNull { it.role == "agent" } ?: return
        sessionRepo.replaceMessage(sessionId, last.id, last.copy(reasoning = last.reasoning + chunk))
    }

    private suspend fun updateAgentText(sessionId: String, text: String) {
        val s = sessionRepo.get(sessionId) ?: return
        val last = s.messages.lastOrNull { it.role == "agent" } ?: return
        sessionRepo.replaceMessage(sessionId, last.id, last.copy(text = text, time = fmtT(System.currentTimeMillis())))
    }

    private suspend fun finishAgent(sessionId: String, stoppedByUser: Boolean = false) {
        val s = sessionRepo.get(sessionId) ?: return
        val last = s.messages.lastOrNull { it.role == "agent" } ?: return
        if (LOG_DB_TRACE) {
            Log.i(
                TAG,
                "finish sid=${sessionId.takeLast(6)} stopped=$stoppedByUser textLen=${last.text.length} " +
                    "cards=${last.tools.size} running=${last.tools.count { it.isRunning }}",
            )
        }
        // 流结束时仍在 running 的卡片 = 未等到 tool_result(流提前结束/用户停止),标记未完成
        val cards = if (last.tools.any { it.isRunning }) {
            last.tools.map { if (it.isRunning) it.copy(state = "error", output = "(未完成)") else it }
        } else last.tools
        // 收尾统一洗一次正文:去掉端点漏出的 chat 模板控制词(如 <|eos|>),
        // 并补上两种「空回复」的说明 —— 用户停止、模型什么都没说(实测都会留下空白气泡)
        val cleaned = com.freebuff.core.model.OutputSanitizer.clean(last.text)
        val finalText = when {
            stoppedByUser -> MsgSteps.stoppedText(cleaned)
            cleaned.isBlank() && cards.isEmpty() -> MsgSteps.EMPTY_REPLY_NOTE
            else -> cleaned
        }
        sessionRepo.replaceMessage(
            sessionId, last.id,
            last.copy(
                text = finalText,
                tools = cards,
                // 过程性步骤(连接/第 N 轮)是临时状态牌:收尾必须清掉,
                // 否则对话结束后会一直挂着「连接模型并开始生成…」(实测残留)
                steps = MsgSteps.withoutTransient(last.steps),
                time = fmtT(System.currentTimeMillis()),
            ),
        )
    }

    /** 更新状态牌的过程步骤:始终只留一个(不堆积),历史性步骤(重试)保持原样。 */
    private suspend fun setProgressStep(sessionId: String, name: String, sub: String) {
        val s = sessionRepo.get(sessionId) ?: return
        val last = s.messages.lastOrNull { it.role == "agent" } ?: return
        val steps = MsgSteps.withoutTransient(last.steps) + MsgStep(name, sub)
        if (steps != last.steps) {
            sessionRepo.replaceMessage(sessionId, last.id, last.copy(steps = steps))
        }
    }

    /** 本轮正文与上一轮完全相同时,把刚追加进消息的这段重复删掉(模型重放场景)。 */
    private suspend fun removeTrailingText(sessionId: String, text: String) {
        val s = sessionRepo.get(sessionId) ?: return
        val last = s.messages.lastOrNull { it.role == "agent" } ?: return
        val t = last.text
        if (text.isNotBlank() && t.endsWith(text)) {
            Log.i(TAG, "dedupe sid=${sessionId.takeLast(6)} drop=${text.length}")
            sessionRepo.replaceMessage(sessionId, last.id, last.copy(text = t.dropLast(text.length)))
        }
    }

    /** 比对正文是否「同一段话」:忽略空白差异。 */
    private fun normText(s: String): String = s.trim().replace(WHITESPACE_RE, " ")

    /** 失败收尾:追加错误文案而非覆盖 —— 保留前面轮次已生成的内容与重试轨迹;时间由随后的 finishAgent 落定。 */
    private suspend fun failAgent(sessionId: String, reason: String) {
        appendAgentText(sessionId, "\n⚠ 请求失败:$reason")
    }
}
