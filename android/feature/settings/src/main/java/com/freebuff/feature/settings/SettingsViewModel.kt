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
import com.freebuff.core.data.repository.SettingsRepository
import com.freebuff.core.data.repository.UpdateRepository
import com.freebuff.core.model.CustomModel
import com.freebuff.core.model.GitState
import com.freebuff.core.model.RemoteVersion
import com.freebuff.core.model.RepairReport
import com.freebuff.core.ui.navigation.AppNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 设置页:主题 / Git 集成 / 自定义模型 / 版本更新。 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
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

    /** 从表单恢复原始记录(清洗前),仅当迁移时报告过修复。 */
    fun restoreRaw() {
        viewModelScope.launch {
            val raw = _repairReport.value?.raw?.filterNotNull() ?: return@launch
            raw.forEach { rec ->
                (rec as? CustomModel)?.let { customModelRepo.add(it) }
            }
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
}
