package com.freebuff.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freebuff.core.data.network.ApiResult
import com.freebuff.core.data.network.ProbeResult
import com.freebuff.core.data.repository.CatalogSource
import com.freebuff.core.data.repository.CustomModelRepository
import com.freebuff.core.data.repository.GitAuthRepository
import com.freebuff.core.data.repository.GitConnectStep
import com.freebuff.core.data.repository.ModelCatalogRepository
import com.freebuff.core.data.repository.SessionRepository
import com.freebuff.core.data.repository.SettingsRepository
import com.freebuff.core.data.repository.UpdateRepository
import com.freebuff.core.model.CustomModel
import com.freebuff.core.model.GitState
import com.freebuff.core.model.RemoteVersion
import com.freebuff.core.model.RepairReport
import com.freebuff.core.model.mergedModelList
import com.freebuff.core.model.uid
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.AppNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 设置页:主题 / Git 集成 / 自定义模型 / 版本更新。 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val sessionRepo: SessionRepository,
    private val customModelRepo: CustomModelRepository,
    private val catalog: ModelCatalogRepository,
    private val gitAuth: GitAuthRepository,
    private val updateRepo: UpdateRepository,
    private val navigator: AppNavigator,
) : ViewModel() {

    val themeMode: StateFlow<String> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "dark")

    val git: StateFlow<GitState> = settings.git
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GitState())

    val repoParse: StateFlow<String> = settings.repoParse
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "strict")

    val customModels: StateFlow<List<CustomModel>> = customModelRepo.models
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val modelId: StateFlow<String> = settings.modelId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "deepseek-v4-flash")

    val version: StateFlow<String> = settings.version
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "0.1.0")

    /* ---------------- 模型 ---------------- */

    /** 当前模型展示名(官方实时/内置 + 自定义),供设置页「模型」组显示。 */
    val modelName: StateFlow<String> = combine(
        catalog.official,
        customModelRepo.models,
        settings.modelId,
    ) { official, customs, id ->
        mergedModelList(official, customs).firstOrNull { it.id == id }?.name ?: id
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun openModelSheet() { navigator.openSheet(AppNavState.SHEET_MODEL) }

    /* ---------------- 会话与数据 ---------------- */

    val sessionCount: StateFlow<Int> = sessionRepo.sessions
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _clearArmed = MutableStateFlow(false)
    val clearArmed: StateFlow<Boolean> = _clearArmed.asStateFlow()

    private var disarmJob: Job? = null

    /**
     * 首次点击只「武装」并在 2.2s 后自动解除(与原型 armedClear 一致),
     * 期间再次点击才真正清除——避免误触直接抹掉全部会话。
     */
    fun armClear() {
        if (_clearArmed.value) return
        _clearArmed.value = true
        disarmJob?.cancel()
        disarmJob = viewModelScope.launch {
            delay(CLEAR_ARM_MS)
            _clearArmed.value = false
        }
    }

    /** 真正清除全部会话(仅在 [clearArmed] 为 true 时由 UI 调用)。 */
    fun clearAllSessions() {
        disarmJob?.cancel()
        _clearArmed.value = false
        viewModelScope.launch {
            sessionRepo.clearAll()
            navigator.showSnack("已清除全部会话")
        }
    }

    /* ---------------- 官方模型目录 ---------------- */

    val catalogSource: StateFlow<CatalogSource> = catalog.source
    val catalogConfigured: Boolean get() = catalog.isGatewayConfigured

    private val _catalogError = MutableStateFlow<String?>(null)
    val catalogError: StateFlow<String?> = _catalogError.asStateFlow()

    /** 手动刷新官方目录:成功提示数量,失败提示分类原因。 */
    fun refreshCatalog() {
        viewModelScope.launch {
            when (val r = catalog.refresh()) {
                is ApiResult.Ok -> {
                    _catalogError.value = null
                    navigator.showSnack("官方目录已更新 · " + r.data.size + " 个模型")
                }
                is ApiResult.Err -> {
                    _catalogError.value = r.error.userMessage
                    navigator.showSnack(r.error.userMessage)
                }
            }
        }
    }

    private val _repairReport = MutableStateFlow<RepairReport?>(null)
    val repairReport: StateFlow<RepairReport?> = _repairReport.asStateFlow()

    private val _repairRestored = MutableStateFlow(false)
    val repairRestored: StateFlow<Boolean> = _repairRestored.asStateFlow()

    /** 启动数据迁移完成后,由 app 层注入清洗报告。 */
    fun setRepairReport(report: RepairReport?) {
        _repairReport.value = report
        _repairRestored.value = false
    }

    /* ---------------- 外观 / 解析 ---------------- */

    fun setTheme(mode: String) = viewModelScope.launch { settings.setThemeMode(mode) }

    fun setRepoParse(mode: String) {
        viewModelScope.launch {
            settings.setRepoParse(mode)
            navigator.showSnack(if (mode == "strict") "已切换:严格解析" else "已切换:宽松原样")
        }
    }

    fun setModelId(id: String) {
        viewModelScope.launch {
            settings.setModelId(id)
            navigator.showSnack("已切换模型")
        }
    }

    /* ---------------- Git 集成 ---------------- */

    val isGitDemo: Boolean get() = gitAuth.isDemo

    /** 当前授权阶段:null 表示未在授权中。 */
    private val _gitConnect = MutableStateFlow<GitConnectStep?>(null)
    val gitConnect: StateFlow<GitConnectStep?> = _gitConnect.asStateFlow()

    private var gitConnectJob: Job? = null

    /**
     * 发起 Git 授权。真实实现走 GitHub 设备流:先上报待授权阶段(用户码 + 验证地址),
     * 轮询到 token 后写入账号状态;演示实现立即完成。
     */
    fun connectGit() {
        gitConnectJob?.cancel()
        _gitConnect.value = null
        gitConnectJob = viewModelScope.launch {
            gitAuth.connect().collect { step ->
                _gitConnect.value = step
                when (step) {
                    is GitConnectStep.Done ->
                        navigator.showSnack(if (gitAuth.isDemo) "已关联 " + step.state.name + "(演示)" else "已关联 " + step.state.name)
                    is GitConnectStep.Failed -> {
                        navigator.showSnack(step.message)
                        _gitConnect.value = null
                    }
                    is GitConnectStep.AwaitingUser -> Unit
                }
            }
        }
    }

    /** 取消进行中的授权轮询。 */
    fun cancelGitConnect() {
        gitConnectJob?.cancel()
        gitConnectJob = null
        _gitConnect.value = null
    }

    fun revokeGit() {
        cancelGitConnect()
        viewModelScope.launch {
            gitAuth.revoke()
            navigator.showSnack("已断开 Git 账号")
        }
    }

    /* ---------------- 自定义模型 ---------------- */

    fun addCustomModel(m: CustomModel) {
        viewModelScope.launch { customModelRepo.add(m) }
    }

    fun updateCustomModel(m: CustomModel) {
        viewModelScope.launch { customModelRepo.update(m) }
    }

    fun deleteCustomModel(id: String) {
        viewModelScope.launch { customModelRepo.delete(id) }
    }

    /** 真实测试连接 + 拉取 /v1/models 快照。 */
    fun probeModel(m: CustomModel, onDone: (Pair<CustomModel, ProbeResult>) -> Unit) {
        viewModelScope.launch {
            val pair = customModelRepo.probe(m)
            if (pair.second is ProbeResult.Success && (pair.second as ProbeResult.Success).note.isNotBlank()) {
                navigator.showSnack((pair.second as ProbeResult.Success).note)
            }
            onDone(pair)
        }
    }

    /** 仅拉取端点 /v1/models 列表(不做连接测试),供表单「拉取列表」快速选模型。 */
    fun fetchModelList(m: CustomModel, onResult: (ApiResult<List<String>>) -> Unit) {
        viewModelScope.launch { onResult(customModelRepo.fetchModels(m)) }
    }

    /**
     * 恢复原始记录(清洗前),仅当迁移时报告过修复。
     * 非对象记录(raw 里的 null)无法落库,自动跳过;ID 为空的原始记录在落库前
     * 重新生成 ID,避免空主键破坏后续的更新/删除。
     */
    fun restoreRaw() {
        viewModelScope.launch {
            val raw = _repairReport.value?.raw ?: return@launch
            raw.filterIsInstance<CustomModel>()
                .forEach { customModelRepo.add(it.copy(id = it.id.ifBlank { "cm-" + uid() })) }
            _repairRestored.value = true
            navigator.showSnack("已恢复原始记录")
        }
    }

    /* ---------------- 版本更新 ---------------- */

    val updateConfigured: Boolean get() = updateRepo.isConfigured

    /** 同意更新:把本地版本号提升到远程版本(真实安装流程为后续接入点)。 */
    fun applyUpdate(newVersion: String) = viewModelScope.launch { settings.setVersion(newVersion) }

    /**
     * 检查更新。
     * @param onResult (远程版本, 是否有更新, 失败文案;失败时前两项为 null/false)
     */
    fun checkForUpdate(onResult: (RemoteVersion?, Boolean, String?) -> Unit) {
        viewModelScope.launch {
            when (val r = updateRepo.check()) {
                is ApiResult.Ok -> onResult(r.data, r.data.isNewerThan(version.value), null)
                is ApiResult.Err -> onResult(null, false, r.error.userMessage)
            }
        }
    }

    private companion object {
        /** 「清除全部会话」二次确认的自动解除时长(毫秒)。 */
        const val CLEAR_ARM_MS = 2200L
    }
}
