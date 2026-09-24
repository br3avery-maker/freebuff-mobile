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
import com.freebuff.core.data.repository.CatalogSource
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

    private val _contextStats = MutableStateFlow<ContextStats?>(null)

    /** 最近一轮上下文构建统计:会话页据此提示「已压缩早期上下文」(见 docs/context-engineering.md §3)。 */
    val contextStats: StateFlow<ContextStats?> = _contextStats.asStateFlow()

    private var streamJob: Job? = null

    /** 当前流式回复所属会话;停止时按它收尾,避免会话切换后落到错误会话。 */
    private var streamSessionId: String? = null

    /** 工具调用单次对话最多执行的轮数(模型→工具→模型 记一轮)。 */
    private companion object {
        const val MAX_TOOL_ROUNDS = 6

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
                    // agent 循环:每轮由 ContextBuilder 重建上下文(记忆+预算),模型可请求工具(最多 MAX_TOOL_ROUNDS 轮)
                    var round = 0
                    val protocol = mutableListOf<ChatMessage>() // 跨轮累积:assistant(tool_calls)+tool 结果(严格协议)
                    while (true) {
                        val roundText = StringBuilder() // 本轮流式输出的文本(回传时作为 assistant content)
                        val base = buildHistory(session.id, ctxRepo, target)
                        val calls = runStreamRound(
                            session.id, target, base + protocol,
                            toolsJson = if (toolsEnabled) DefaultTools.toJsonArrayString(activeTools()) else "",
                            textSink = roundText,
                        ) ?: break // 用户停止
                        // 流结束:若有工具调用且未超轮次,执行并回传,继续下一轮
                        if (calls.isEmpty() || !toolsEnabled || round >= MAX_TOOL_ROUNDS) {
                            if (calls.isNotEmpty() && round >= MAX_TOOL_ROUNDS) {
                                appendAgentText(session.id, "\n\n(工具调用轮次已达上限 $MAX_TOOL_ROUNDS,停止继续执行)")
                            }
                            break
                        }
                        round++
                        // 执行工具;结果写入工具卡片(UI)+ 压缩后进入协议消息(下一轮请求)
                        protocol += ChatMessage.assistantWithCalls(roundText.toString(), calls)
                        for (c in calls) {
                            val outcome = dispatchTool(c)
                            completeToolCard(session.id, c.callId, outcome.content, outcome.isError)
                            protocol += ChatMessage.toolResult(c.callId, ContextPolicy.compressToolResult(outcome.content))
                        }
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
            // 轮次结束后的记忆提取:独立协程执行 —— 它是额外一次 LLM 调用,
            // 不能让「正在生成」状态与输入框被它拖住(提取失败静默)
            viewModelScope.launch { extractTurnMemories(session.id, target) }
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

    /**
     * 构建请求上下文:记忆块 + 会话历史,经 ContextBuilder 预算裁剪与压缩。
     * 每轮循环重建一次 —— 工具结果落库后,下一轮自动进入上下文。
     */
    private suspend fun buildHistory(sessionId: String, ctxRepo: String, target: ChatTarget): List<ChatMessage> {
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
        val built = contextBuilder.build(
            memoryBlocks = if (memoryEnabled) memoryRepo.load() else emptyList(),
            session = msgs,
            budget = budget,
            summarize = llmSummarizer(target),
            workingMemory = workingMemory,
            userId = MemoryStore.LOCAL_USER_ID,
        )
        _contextStats.value = built.stats
        val out = built.messages.toMutableList()
        if (ctxRepo.isNotBlank()) {
            // 任务向导携带的仓库上下文:插在 system 之后,不落库
            out.add(1, ChatMessage.text("system", "本次任务关联仓库:$ctxRepo"))
        }
        return out
    }

    /**
     * LLM 摘要压缩执行器(LibreChat 式):把被裁掉的早期消息交给模型生成摘要。
     * 复用流式管道收集文本;失败由调用方回退提取式摘要。
     */
    private fun llmSummarizer(target: ChatTarget): (suspend (List<com.freebuff.core.data.context.ContextTurn>) -> String)? {
        if (!target.isConfigured) return null
        return { turns ->
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
                    ChatMessage.text("system", "把以下对话压缩成简洁摘要,保留:用户的最终目标、已确定的关键决策、重要数据与结论、尚未完成的事项。直接输出摘要正文,不要客套。"),
                    ChatMessage.text("user", convo.take(24000)),
                ),
            ).collect { ev ->
                if (ev is com.freebuff.core.model.AgentEvent.Text) sb.append(ev.chunk)
            }
            sb.toString().trim()
        }
    }

    /** 端侧工具分派:记忆类工具走本地仓库(save_memory 自编辑块 / memory_recall 检索),其余交给 ToolExecutors。 */
    private suspend fun dispatchTool(c: com.freebuff.core.model.ToolCallReq): com.freebuff.core.model.ToolOutcome =
        when (c.name) {
            "save_memory" -> executeSaveMemory(c)
            "memory_recall" -> executeMemoryRecall(c)
            else -> tools.execute(c)
        }

    /**
     * memory_recall 工具端侧执行:按 user_id/query/top_k/memory_type 检索记忆库。
     * 参数缺失或非法时回传明确错误(模型可修正后重试);非本机 user_id 命中为空时回退本机记忆。
     */
    private suspend fun executeMemoryRecall(c: com.freebuff.core.model.ToolCallReq): com.freebuff.core.model.ToolOutcome {
        val a = MemoryRecallCodec.parse(c.argsJson)
        if (a.userId.isBlank()) return com.freebuff.core.model.ToolOutcome("检索失败:缺少 user_id 参数", isError = true)
        if (a.query.isBlank()) return com.freebuff.core.model.ToolOutcome("检索失败:缺少 query 参数", isError = true)
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
    private suspend fun executeSaveMemory(c: com.freebuff.core.model.ToolCallReq): com.freebuff.core.model.ToolOutcome {
        val a = MemoryCodec.parseSaveArgs(c.argsJson)
        return try {
            com.freebuff.core.model.ToolOutcome(memoryRepo.applySave(a.block, a.content, a.replace))
        } catch (e: Exception) {
            com.freebuff.core.model.ToolOutcome("保存记忆失败:" + (e.message ?: e::class.java.simpleName), isError = true)
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
                steps = last.steps + MsgStep("重试 " + retry, "上次失败:" + lastError + ";退避后自动重试"),
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

    /** 失败收尾:追加错误文案而非覆盖 —— 保留前面轮次已生成的内容与重试轨迹;时间由随后的 finishAgent 落定。 */
    private suspend fun failAgent(sessionId: String, reason: String) {
        appendAgentText(sessionId, "\n⚠ 请求失败:$reason")
    }
}
