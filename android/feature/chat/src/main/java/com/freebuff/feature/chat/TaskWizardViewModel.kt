package com.freebuff.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freebuff.core.data.network.ApiResult
import com.freebuff.core.data.repository.CustomModelRepository
import com.freebuff.core.data.repository.GitAuthRepository
import com.freebuff.core.data.repository.ModelCatalogRepository
import com.freebuff.core.data.repository.SessionRepository
import com.freebuff.core.data.repository.SettingsRepository
import com.freebuff.core.model.CustomModel
import com.freebuff.core.model.GitState
import com.freebuff.core.model.OfficialModel
import com.freebuff.core.model.RepoItem
import com.freebuff.core.model.TaskDraft
import com.freebuff.core.model.mergedModelList
import com.freebuff.core.model.parseClone
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.AppNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * 发起任务三步向导:仓库 → 模型 → 描述。草稿状态本模块持有,
 * 最终提交时创建会话并交给对话页(根层触发真实对话)。
 */
@HiltViewModel
class TaskWizardViewModel @Inject constructor(
    private val sessionRepo: SessionRepository,
    private val customModelRepo: CustomModelRepository,
    private val catalog: ModelCatalogRepository,
    private val gitAuth: GitAuthRepository,
    private val settings: SettingsRepository,
    private val navigator: AppNavigator,
) : ViewModel() {

    private val _draft = MutableStateFlow(TaskDraft())
    val draft: StateFlow<TaskDraft> = _draft.asStateFlow()

    /** Git 账号仓库(真实 OAuth 查询;未配置时为演示回退)。 */
    private val reposFlow = MutableStateFlow<List<RepoItem>>(emptyList())
    val repos: StateFlow<List<RepoItem>> = reposFlow.asStateFlow()

    private val _reposLoading = MutableStateFlow(false)
    val reposLoading: StateFlow<Boolean> = _reposLoading.asStateFlow()

    private val _reposError = MutableStateFlow<String?>(null)
    val reposError: StateFlow<String?> = _reposError.asStateFlow()

    init {
        loadRepos()
    }

    /** 拉取 Git 仓库列表;失败时保留为空并给出可展示的错误文案。 */
    fun loadRepos() {
        viewModelScope.launch {
            _reposLoading.value = true
            _reposError.value = null
            when (val r = gitAuth.repos()) {
                is ApiResult.Ok -> reposFlow.value = r.data
                is ApiResult.Err -> {
                    reposFlow.value = emptyList()
                    _reposError.value = r.error.userMessage
                }
            }
            _reposLoading.value = false
        }
    }

    val customModels: StateFlow<List<CustomModel>> = customModelRepo.models
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val modelList: StateFlow<List<OfficialModel>> = combine(catalog.official, customModels) { official, customs ->
        mergedModelList(official, customs)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        mergedModelList(catalog.official.value, customModels.value),
    )

    val repoParse: StateFlow<String> = settings.repoParse
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "strict")

    val git: StateFlow<GitState> = settings.git
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GitState())

    /** 提交互斥:launch 的 viewModelScope.launch 有挂起点,双击可在同一帧内双双通过 isBlank 校验。 */
    private val launching = AtomicBoolean(false)

    fun update(transform: (TaskDraft) -> TaskDraft) { _draft.update(transform) }

    fun prev() { _draft.update { it.copy(step = it.step - 1) } }

    fun next() {
        val d = _draft.value
        val ok = when (d.repoMode) {
            "manual" -> d.repoUrl.isNotBlank() &&
                (repoParse.value != "strict" || parseClone(d.repoUrl) != null || d.repoUrl.contains("/"))
            "git" -> d.repoName.isNotBlank()
            else -> true
        }
        if (ok) _draft.update { it.copy(step = 1) }
        else navigator.showSnack(if (d.repoMode == "git") "请先选择一个仓库" else "仓库地址不完整")
    }

    /**
     * 提交:创建会话 → 关闭向导 → 切到对话页,并通过 [onLaunched] 通知根层
     * 触发首条消息的真实对话。
     */
    fun launch(onLaunched: (sessionId: String, desc: String, ctxRepo: String, ctxModel: String) -> Unit) {
        val d = _draft.value
        if (d.desc.isBlank()) {
            navigator.showSnack("请先描述任务")
            return
        }
        if (d.desc.length > DESC_MAX) {
            navigator.showSnack("任务描述不能超过 " + DESC_MAX + " 字")
            return
        }
        // 快速双击守卫:提交中有挂起点,不加锁会创建两个会话
        if (!launching.compareAndSet(false, true)) return
        val ctxRepo = when (d.repoMode) {
            "manual" -> d.repoUrl.ifBlank { d.repoName }
            "git" -> d.repoName
            else -> ""
        }
        val ctxModel = modelList.value.firstOrNull { it.id == d.modelId }?.name ?: d.modelId
        viewModelScope.launch {
            try {
                // 把向导所选模型写回设置,首条对话按此模型真实请求
                settings.setModelId(d.modelId)
                val s = sessionRepo.create("新对话")
                val desc = d.desc
                _draft.value = TaskDraft()
                navigator.closeSheet()
                navigator.openSession(s.id)
                navigator.navigate(AppNavState.ROUTE_CHAT)
                onLaunched(s.id, desc, ctxRepo, ctxModel)
            } finally {
                launching.set(false)
            }
        }
    }

    private companion object {
        /** 任务描述上限(与 UI 字数计数一致)。 */
        const val DESC_MAX = 500
    }
}
